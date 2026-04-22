package com.kodedu.config;

import com.dooapp.fxform.FXForm;
import com.dooapp.fxform.builder.FXFormBuilder;
import com.kodedu.config.AsciidoctorConfigBase.LoadedAttributes;
import com.kodedu.controller.ApplicationController;
import com.kodedu.service.ThreadService;
import com.kodedu.service.preview.PreviewScope;

import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ResourceBundle;

/**
 * Created by usta on 19.07.2015.
 */
@Component
public class PreviewConfigBean extends AsciidoctorConfigBase<PreviewConfigBean.PreviewConfigAttributes> {

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

    /**
     * When true, the PDF preview re-renders automatically after typing
     * pauses (debounced). When false, only the manual refresh button /
     * F5 triggers a render — useful on large books where each render
     * is expensive.
     */
    private final BooleanProperty pdfPreviewAutoRender =
            new SimpleBooleanProperty(true);

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

    // --- persistence -------------------------------------------------------

    @Override
    protected PreviewConfigAttributes loadAdditionalAttributes(JsonObject jsonObject) {
        PreviewConfigAttributes attrs = new PreviewConfigAttributes();
        String backendStr = jsonObject.getString("previewBackend", PreviewBackend.PDF.name());
        try {
            attrs.previewBackend = PreviewBackend.valueOf(backendStr);
        } catch (IllegalArgumentException ignored) {
            attrs.previewBackend = PreviewBackend.PDF;
        }
        String scopeStr = jsonObject.getString("pdfPreviewScope", PreviewScope.CHAPTER.name());
        try {
            attrs.pdfPreviewScope = PreviewScope.valueOf(scopeStr);
        } catch (IllegalArgumentException ignored) {
            attrs.pdfPreviewScope = PreviewScope.CHAPTER;
        }
        attrs.pdfPreviewAutoRender = jsonObject.getBoolean("pdfPreviewAutoRender", true);
        return attrs;
    }

    @Override
    protected void fxSetAdditionalAttributes(PreviewConfigAttributes attrs) {
        setPreviewBackend(attrs.previewBackend);
        setPdfPreviewScope(attrs.pdfPreviewScope);
        setPdfPreviewAutoRender(attrs.pdfPreviewAutoRender);
    }

    @Override
    protected void addAdditionalAttributesToJson(JsonObjectBuilder objectBuilder) {
        objectBuilder.add("previewBackend", getPreviewBackend().name());
        objectBuilder.add("pdfPreviewScope", getPdfPreviewScope().name());
        objectBuilder.add("pdfPreviewAutoRender", isPdfPreviewAutoRender());
    }

    // --- settings dialog ---------------------------------------------------

    @Override
    public FXForm getConfigForm() {
        return new FXFormBuilder<>()
                .resourceBundle(ResourceBundle.getBundle("asciidoctorConfig"))
                .includeAndReorder("previewBackend", "pdfPreviewScope", "pdfPreviewAutoRender", "attributes")
                .build();
    }

    // --- properties --------------------------------------------------------

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

    public boolean isPdfPreviewAutoRender() {
        return pdfPreviewAutoRender.get();
    }

    public BooleanProperty pdfPreviewAutoRenderProperty() {
        return pdfPreviewAutoRender;
    }

    public void setPdfPreviewAutoRender(boolean value) {
        pdfPreviewAutoRender.set(value);
    }

    public static class PreviewConfigAttributes implements LoadedAttributes {
        PreviewBackend previewBackend;
        PreviewScope pdfPreviewScope;
        boolean pdfPreviewAutoRender = true;
    }
}
