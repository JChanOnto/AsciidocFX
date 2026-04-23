# AGENTS.md — AsciidocFX Agent Workflow

These rules apply to every code-changing task an AI agent performs in this
repository (feature work, bug fixes, refactors). They are **mandatory**, not
suggestions. Skipping a step is a defect.

## The Test Discipline

After every feature added or bug fixed, the agent **must** complete the
following sequence before declaring the task done. There are no exceptions
for "small" changes — small changes ship the most regressions.

### 1. Audit existing test coverage

Search the test tree (`src/test/java/**`) for tests that exercise the
behaviour you just changed. A test "covers" the change if it would have
failed against the bug (for fixes) or asserts the new behaviour (for
features).

A passing pre-existing test that merely *touches* the changed file is
**not** sufficient coverage. The test must assert the specific behaviour
that was added or fixed.

### 2. Add high-quality tests when coverage is missing

If no test asserts the changed behaviour, write one. Quality bar:

- **Non-trivial.** A test that asserts `true == true` after the change is
  worse than no test — it gives false confidence. Reproduce the actual
  scenario: real input shapes, real edge cases, real failure modes.
- **Regression-shaped for fixes.** Bug-fix tests should fail against the
  pre-fix code and pass against the post-fix code. Mentally run the test
  against the old code; if it would have passed, the test is wrong.
- **One behaviour per test.** Don't bundle three assertions into one
  `@Test` so a single failure obscures which contract broke.
- **Pure where possible.** Prefer tests in `com.kodedu.service.*`
  (Spring/JavaFX-free) over tests that need the full app context. The
  existing `PreviewSourceResolverTest` and `MasterDocResolverTest` are
  the model — pure inputs, deterministic outputs, `@TempDir` for
  filesystem.
- **Named for the contract.** `chapterScopeSuppressesTitlePageTocAndSectnums`
  reads as a spec line. `testStuff` does not.

### 3. Run the new test in isolation, fix until green

```pwsh
mvn -DfailIfNoTests=false "-Dtest=YourNewTest" "-Djacoco.skip=true" test
```

If it fails:

- **Diagnose the root cause first.** Read the assertion message, read the
  stack trace, and form a hypothesis about *why* the production code
  produced the wrong answer. Do not change the test until you understand
  the failure.
- **Fix the right side of the equation.** If the production code is
  wrong, fix the production code. If the test's expected value is wrong
  (because you misread the spec), fix the test — but only after you can
  articulate, in one sentence, *why* the original expectation was wrong.
- **Never** make a test pass by deleting the assertion, loosening it to
  tautology (`assertTrue(true)`, `assertNotNull(x)` where `x` was the
  thing you were supposed to verify), wrapping it in `try {} catch
  (Throwable ignored) {}`, or commenting it out.
- **Never** make a test pass by short-circuiting the production code so
  the test's input no longer exercises the real code path.

### 4. Run the full test suite, fix any new breakage

```pwsh
mvn "-Djacoco.skip=true" test
```

If anything that was previously green is now red:

- Read the failure. Determine whether your change *broke real behaviour*
  (production bug — fix the production code) or *invalidated a stale
  test assumption* (test bug — fix the test, but justify why the old
  assumption was wrong).
- Apply the fix where the root cause lives, not where it's most
  convenient to silence the failure.
- Re-run the full suite. Iterate until everything is green.

### 5. Verify before declaring done

Before calling `task_complete`:

- The new test exists, asserts the changed behaviour, and passes.
- The full test suite passes.
- You can state, in one sentence each: what behaviour the new test
  pins, and (for fixes) what the root cause of the original bug was.

## Anti-Patterns That Will Get You Caught

- **"Tests pass on my machine"** — always run them via `mvn test`.
- **"This change is too small for a test"** — those are the changes that
  break in production.
- **"The existing tests already cover this"** — verify by reading the
  existing assertions, not by hoping.
- **"I'll fix the failing test by adjusting the expected value"** — only
  acceptable if you can articulate why the original expectation was
  wrong. Otherwise you're papering over a regression.
- **"The test is flaky, I re-ran it and it passed"** — flaky tests are
  bugs. Either deflake the test or deflake the code under test; do not
  ignore.
- **"The full suite has pre-existing failures unrelated to my change"** —
  record which failures were pre-existing (with output proving it) and
  confirm none are newly introduced. If you can't tell, run `git stash &&
  mvn test` against `HEAD` to baseline.

## Repository-Specific Notes

- **JaCoCo + Java 25 incompatibility.** `mvn test` will fail in the
  `jacoco-maven-plugin:report` phase with `Unsupported class file major
  version 69` even when all tests pass. Pass `-Djacoco.skip=true` to
  skip the coverage report — the test results above it are still
  authoritative.
- **JavaFX/Spring tests are heavy.** Prefer pure Java tests in
  `service/`, `helper/`, `config/` (where the class under test doesn't
  need a running FX toolkit). The existing
  `PreviewSourceResolverTest`, `MasterDocResolverTest`,
  `AsciidoctorConfigLoaderTest`, `ProjectConfigDiscoveryTest`, and
  `ExecutableResolverTest` are the templates to copy from.
- **Test naming.** `<methodOrFeature><Scenario>` reads as a spec line.
  See `chapterScopeSuppressesTitlePageTocAndSectnums`,
  `findReferrersReturnsAllBooksThatIncludeSharedChapter`,
  `isReachableHandlesCycles`.
- **Use `@TempDir`** for any filesystem fixture. Never write into
  `src/test/resources` from a test, never assume a fixed path on disk.
