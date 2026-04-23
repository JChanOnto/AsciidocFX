package com.kodedu.service.convert.pdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the single-source-of-truth for Asciidoctor intrinsic
 * location/time attributes: {@link PdfRenderer#INTRINSIC_LOCATION_ATTRS}.
 *
 * <p>Historically this set was duplicated verbatim in
 * {@code AsciidoctorConfigBase}; two copies meant a bug-fix in one
 * spot silently drifted from the other.  Both sites now reference
 * {@code PdfRenderer.INTRINSIC_LOCATION_ATTRS}, and this test guards
 * the documented set.
 */
class IntrinsicLocationAttrsContractTest {

    @Test
    void setContainsEveryDocumentedLocationAndTimeAttribute() {
        var set = PdfRenderer.INTRINSIC_LOCATION_ATTRS;
        for (String expected : new String[]{
                "docdir", "docfile", "docname", "docfilesuffix",
                "docdate", "doctime", "docyear", "doctimestamp",
                "localdate", "localtime", "localyear", "localdatetime"}) {
            assertTrue(set.contains(expected),
                    expected + " must be in the intrinsic attr set; missing would "
                            + "allow it to leak through both the config-level "
                            + "ignore-list and the renderer's final strip");
        }
    }

    @Test
    void setHasNoAccidentalExtraAttributes() {
        // Any addition should be deliberate and documented.  If this
        // fires, update the expected list above - don't just bump the
        // count.  This catches accidental `add` calls that would
        // silently start dropping legitimate user attributes.
        assertFalse(PdfRenderer.INTRINSIC_LOCATION_ATTRS.size() > 12,
                "set grew to " + PdfRenderer.INTRINSIC_LOCATION_ATTRS.size()
                        + " entries: " + PdfRenderer.INTRINSIC_LOCATION_ATTRS);
    }
}
