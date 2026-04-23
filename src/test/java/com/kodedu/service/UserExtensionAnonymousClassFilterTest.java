package com.kodedu.service;

import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the
 * <pre>(NameError) wrong constant name #&lt;Class:0x1ad2db1c&gt;</pre>
 * crash that hit users whose project Ruby extensions used the block
 * form of Asciidoctor's extension DSL:
 * <pre>{@code
 *   Asciidoctor::Extensions.register do
 *     treeprocessor do
 *       process do |doc| ... end
 *     end
 *   end
 * }</pre>
 *
 * <p>That DSL produces an <em>anonymous</em> {@code Class.new(Treeprocessor)}.
 * {@link UserExtension}'s subclass-walk would call
 * {@code cc.getName()} on it, get back the JRuby inspect placeholder
 * {@code "#<Class:0x...>"}, and pass that string to
 * {@code extensionGroup.rubyTreeprocessor(name)} — which generates Ruby
 * code that does {@code Object.const_get("#<Class:0x...>")} and blows up.
 *
 * <p>The fix is the {@code isNamedRubyClass} predicate, which uses the
 * canonical Ruby anonymous-class check ({@code module.name.nil?} —
 * {@link RubyClass#getBaseName()} returns {@code null} in JRuby).  These
 * tests pin its contract using real JRuby objects.
 */
class UserExtensionAnonymousClassFilterTest {

    private static boolean invokeIsNamed(RubyClass cls) throws Exception {
        Method m = UserExtension.class.getDeclaredMethod("isNamedRubyClass", RubyClass.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, cls);
    }

    @Test
    void anonymousRubyClassIsRejected() throws Exception {
        Ruby ruby = Ruby.newInstance();
        // `Class.new(Object)` — exactly what Asciidoctor's
        // `treeprocessor do ... end` block form produces under the hood.
        RubyClass anon = (RubyClass) ruby.evalScriptlet("Class.new(Object)");

        assertFalse(invokeIsNamed(anon),
                "Anonymous classes (Ruby `name` is nil, JRuby getName "
                        + "returns the #<Class:0x...> placeholder) must be "
                        + "rejected so they don't reach rubyTreeprocessor() "
                        + "and crash with `wrong constant name`.");
    }

    @Test
    void namedRubyClassIsAccepted() throws Exception {
        Ruby ruby = Ruby.newInstance();
        // Real-world shape: a named subclass like a user might write
        // outside the block-form DSL.
        ruby.evalScriptlet("module TrueadcDocs; class MyTreeProc; end; end");
        RubyClass named = (RubyClass) ruby.evalScriptlet("TrueadcDocs::MyTreeProc");

        assertTrue(invokeIsNamed(named),
                "Named classes must pass through so they're registered "
                        + "via the subclass-walk.  Got name=" + named.getName()
                        + " baseName=" + named.getBaseName());
    }

    @Test
    void anonymousNestedInsideBlockFormIsAlsoRejected() throws Exception {
        Ruby ruby = Ruby.newInstance();
        // Reproduce the exact shape from the user's repro:
        //   register do
        //     treeprocessor do ... end
        //   end
        // produces an anonymous Class.new(Treeprocessor).  Without
        // Asciidoctor loaded we simulate by subclassing Object inside
        // a block — same anonymous-class outcome from JRuby's POV.
        RubyClass anon = (RubyClass) ruby.evalScriptlet(
                "result = nil; lambda { result = Class.new(Object) }.call; result");
        assertFalse(invokeIsNamed(anon),
                "Anonymous classes created inside a block must also be "
                        + "rejected; baseName=" + anon.getBaseName()
                        + " name=" + anon.getName());
    }
}
