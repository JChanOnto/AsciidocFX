package com.kodedu.config;

import com.kodedu.config.AsciidoctorConfigBase.NoAttributes;
import com.kodedu.controller.ApplicationController;
import com.kodedu.service.ThreadService;
import com.kodedu.service.preview.PreviewScope;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Created by usta on 19.07.2015.
 */
@Component
public class PreviewConfigBean extends AsciidoctorConfigBase<NoAttributes> {

    private final ApplicationController controller;
    private final ThreadService threadService;

    /**
     * Selects the backend for the right-hand preview pane.
     *
     * <p>Default is {@link PreviewBackend#PDF} — the byte-identical preview
     * of what "Save → PDF" produces.  Users who want a faster (but only
     * CSS-approximate) preview can flip this to {@link PreviewBackend#HTML}.
     */
    private final ObjectProperty<PreviewBackend> previewBackend =
            new SimpleObjectProperty<>(PreviewBackend.PDF);

    /**
     * Scope of the PDF preview render: CHAPTER (fast, active chapter only)
     * or FULL (canonical, entire master document).  Ignored when the backend
     * is HTML.
     * @see com.kodedu.service.preview.PreviewSourceResolver
     */
    private final ObjectProperty<PreviewScope> pdfPreviewScope =
            new SimpleObjectProperty<>(PreviewScope.CHAPTER);

    @Override
    public String formName() {
        return "Preview Settings";
    }

    @Autowired
    public PreviewConfigBean(ApplicationController controller, ThreadService threadService) {
        super(controller, threadService);
        this.controller = controller;
        this.threadService = threadService;
    }

    @Override
    public Path getConfigPath() {
        return super.resolveConfigPath("asciidoctor_preview.json");
    }

    public PreviewBackend getPreviewBackend() {
        return previewBackend.get();
    }

    public ObjectProperty<PreviewBackend> previewBackendProperty() {
        return previewBackend;
    }

    public void setPreviewBackend(PreviewBackend backend) {
        previewBackend.set(backend == null ? PreviewBackend.PDF : backend);
    }

    public PreviewScope getPdfPreviewScope() {
        return pdfPreviewScope.get();
    }

    public ObjectProperty<PreviewScope> pdfPreviewScopeProperty() {
        return pdfPreviewScope;
    }

    public void setPdfPreviewScope(PreviewScope scope) {
        pdfPreviewScope.set(scope == null ? PreviewScope.CHAPTER : scope);
    }
}
