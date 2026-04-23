package com.kodedu.service.convert.pdf;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the chapter-preview "include file not found"
 * bug: the synthesised wrapper is parsed for attribute extraction with
 * the chapter file's directory as {@code baseDir}, which sets the
 * intrinsic {@code docdir} to the chapter's directory.  If that
 * {@code docdir} (or any of its siblings) leaks into the
 * {@code Attributes} map handed to the actual render, the renderer's
 * own {@code baseDir} is overridden and {@code include::sections/...[]}
 * resolves under the wrong root, producing
 * {@code <projectRoot>/sections/sections/01-overview.adoc}.
 *
 * <p>{@link PdfRenderer#stripIntrinsicLocationAttrs(Map)} is the
 * defence-in-depth strip that runs immediately before the attributes
 * are passed to either the in-process JRuby render or the external
 * CRuby CLI.  These tests pin its contract.
 */
class PdfRendererIntrinsicAttrStripTest {

    @Test
    void docdirIsStrippedSoItCannotOverrideRendererBaseDir() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("docdir", "/tmp/project/sections");
        in.put("pdf-theme", "custom");

        Map<String, Object> out = PdfRenderer.stripIntrinsicLocationAttrs(in);

        assertFalse(out.containsKey("docdir"),
                "docdir must never reach the renderer; it overrides baseDir "
                        + "and breaks include:: resolution");
        assertEquals("custom", out.get("pdf-theme"),
                "non-intrinsic attributes must pass through untouched");
    }

    @Test
    void allDocumentedIntrinsicLocationAndTimeAttrsAreStripped() {
        Map<String, Object> in = new LinkedHashMap<>();
        for (String key : PdfRenderer.INTRINSIC_LOCATION_ATTRS) {
            in.put(key, "leaked-value-for-" + key);
        }
        in.put("safe-passthrough", "kept");

        Map<String, Object> out = PdfRenderer.stripIntrinsicLocationAttrs(in);

        for (String key : PdfRenderer.INTRINSIC_LOCATION_ATTRS) {
            assertFalse(out.containsKey(key),
                    "intrinsic attribute '" + key + "' must be stripped");
        }
        assertEquals("kept", out.get("safe-passthrough"));
        assertEquals(1, out.size(),
                "only the non-intrinsic attribute should remain");
    }

    @Test
    void stripCoversTheFullDocumentedIntrinsicSet() {
        // Belt-and-suspenders: if a future Asciidoctor release adds a new
        // intrinsic location/time attribute and we forget to add it here,
        // this test will not catch it - but it does pin the *current*
        // contract so accidental deletions from the set are caught.
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("docdir"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("docfile"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("docname"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("docfilesuffix"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("docdate"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("doctime"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("docyear"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("doctimestamp"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("localdate"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("localtime"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("localyear"));
        assertTrue(PdfRenderer.INTRINSIC_LOCATION_ATTRS.contains("localdatetime"));
    }

    @Test
    void emptyMapIsReturnedAsIsWithoutAllocation() {
        Map<String, Object> empty = new LinkedHashMap<>();
        Map<String, Object> out = PdfRenderer.stripIntrinsicLocationAttrs(empty);
        assertSame(empty, out, "empty input must be returned as-is (no copy)");
    }

    @Test
    void nullMapIsTolerated() {
        assertNull(PdfRenderer.stripIntrinsicLocationAttrs(null));
    }

    @Test
    void mapWithoutAnyIntrinsicAttrsIsReturnedUnchanged() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("pdf-theme", "custom");
        in.put("source-highlighter", "rouge");

        Map<String, Object> out = PdfRenderer.stripIntrinsicLocationAttrs(in);

        assertEquals(in, out);
    }
}
