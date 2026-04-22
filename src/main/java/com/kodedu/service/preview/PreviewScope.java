package com.kodedu.service.preview;

/**
 * Scope of the live PDF preview render.
 *
 * @see PreviewSourceResolver
 */
public enum PreviewScope {
    /** Render only the active chapter (fast, ~1–3s; page numbers/TOC won't match final). */
    CHAPTER,
    /** Render the entire master document (canonical, slow, ~tens of seconds). */
    FULL
}
