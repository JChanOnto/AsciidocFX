package com.kodedu.config;

import java.nio.file.Path;
import java.util.ResourceBundle;

import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import org.asciidoctor.Asciidoctor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.dooapp.fxform.FXForm;
import com.dooapp.fxform.builder.FXFormBuilder;
import com.dooapp.fxform.filter.ExcludeFilter;
import com.kodedu.config.AsciidoctorConfigBase.LoadedAttributes;
import com.kodedu.config.PdfConfigBean.PdfConfigAttributes;
import com.kodedu.controller.ApplicationController;
import com.kodedu.service.ThreadService;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;


@Component
public class PdfConfigBean extends AsciidoctorConfigBase<PdfConfigAttributes> {

    private final ApplicationController controller;
    private final ThreadService threadService;

    private ObjectProperty<PdfConverterType> converter = new SimpleObjectProperty<>(PdfConverterType.FOP);

    /**
     * External command to use for PDF rendering instead of the built-in
     * JRuby {@code asciidoctorj-pdf} pipeline.  Leave blank to use the
     * in-process JRuby path (or, if the install4j package shipped with a
     * bundled CRuby runtime, the auto-detected
     * {@code <install-dir>/ruby/bin/asciidoctor-pdf}).
     *
     * <p>When set, AsciidocFX runs this command via {@code ProcessBuilder}
     * and forwards every resolved attribute as a {@code -a name=value} flag.
     * CRuby Prawn is typically 2-5x quicker than JRuby Prawn.
     *
     * <p>Examples: {@code bundle exec asciidoctor-pdf} /
     * {@code asciidoctor-pdf} /
     * {@code C:\Ruby34-x64\bin\bundle.bat exec asciidoctor-pdf}.
     *
     * <p>Split on whitespace (no shell quoting); use absolute paths if any
     * token would contain a space.
     */
    private final StringProperty pdfRendererCommand = new SimpleStringProperty("");

    private final ExcludeFilter attributeExclusion = new ExcludeFilter("attributes");

	@Override
    public String formName() {
        return "PDF Settings";
    }

    @Autowired
    public PdfConfigBean(ApplicationController controller, ThreadService threadService) {
        super(controller, threadService);
        this.controller = controller;
        this.threadService = threadService;
	}

    @Override
    public Path getConfigPath() {
        return super.resolveConfigPath("asciidoctor_pdf.json");
    }

	@Override
	protected PdfConfigAttributes loadAdditionalAttributes(JsonObject jsonObject) {
		var attributes = new PdfConfigAttributes();
		String converterStr = jsonObject.getString("converter", PdfConverterType.FOP.name());
		if (PdfConverterType.contains(converterStr)) {
			attributes.converter = PdfConverterType.valueOf(converterStr);
		}
		attributes.pdfRendererCommand = jsonObject.getString("pdfRendererCommand", "");
		return attributes;
	}
	
    @Override
	protected void fxSetAdditionalAttributes(PdfConfigAttributes childClassAttributes) {
		setPdfConverterType(childClassAttributes.converter);
		setPdfRendererCommand(childClassAttributes.pdfRendererCommand);
	}
    

	@Override
	protected void addAdditionalAttributesToJson(JsonObjectBuilder objectBuilder) {
		objectBuilder.add("converter", getPdfConverterType().name());
		objectBuilder.add("pdfRendererCommand", getPdfRendererCommand() == null ? "" : getPdfRendererCommand());
	}

	@Override
	public FXForm getConfigForm() {
		FXForm configForm = new FXFormBuilder<>().resourceBundle(ResourceBundle.getBundle("asciidoctorConfig"))
		                                         .includeAndReorder("converter", "pdfRendererCommand", "attributes")
		                                         .build();

		this.converter.addListener((obs, oldValue, newValue) -> {
			performFilters(configForm, newValue);
		});
		performFilters(configForm, getPdfConverterType());
		return configForm;
	}

	public PdfConverterType getPdfConverterType() {
        return converter.get();
    }

    public ObjectProperty<PdfConverterType> pdfConverterTypeProperty() {
        return converter;
    }

	public void setPdfConverterType(PdfConverterType pdfConverterType) {
		if (pdfConverterType != null) {
			this.converter.set(pdfConverterType);
		}
	}

	public String getPdfRendererCommand() {
		return pdfRendererCommand.get();
	}

	public StringProperty pdfRendererCommandProperty() {
		return pdfRendererCommand;
	}

	public void setPdfRendererCommand(String value) {
		this.pdfRendererCommand.set(value == null ? "" : value);
	}
    
    
    private void performFilters(FXForm configForm, PdfConverterType type) {
		if (PdfConverterType.ASCIIDOCTOR.equals(type)) {
			Platform.runLater(() -> configForm.getFilters().remove(attributeExclusion));
		} else {
			Platform.runLater(() -> configForm.addFilters(attributeExclusion));
		}
	}


	public static class PdfConfigAttributes implements LoadedAttributes {
    	PdfConverterType converter;
    	String pdfRendererCommand;
    }
    
    
}
