package com.kodedu.config;

/**
 * Which backend powers the right-hand preview pane.
 *
 * <p>The PDF preview is byte-identical to the "Save → PDF" output (Prawn
 * layout, real fonts, real pagination).  The HTML preview is faster but only
 * approximates the final document.
 */
public enum PreviewBackend {
    /** Render via {@code asciidoctor-pdf} + PDFBox rasterization (1:1 with export). */
    PDF,
    /** Render via {@code asciidoctor.js} + WebKit (fast, CSS approximation). */
    HTML
}
