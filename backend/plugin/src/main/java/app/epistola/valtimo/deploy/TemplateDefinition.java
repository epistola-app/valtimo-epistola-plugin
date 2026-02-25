package app.epistola.valtimo.deploy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Model for a template definition file loaded from classpath.
 * Files are expected at: config/epistola/templates/{slug}/definition.json
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TemplateDefinition(
        @JsonProperty("slug") String slug,
        @JsonProperty("name") String name,
        @JsonProperty("version") String version,
        @JsonProperty("dataModel") JsonNode dataModel,
        @JsonProperty("dataExamples") List<DataExample> dataExamples,
        @JsonProperty("templateModel") JsonNode templateModel,
        @JsonProperty("variants") List<VariantDefinition> variants,
        @JsonProperty("publishTo") List<String> publishTo
) {
    public TemplateDefinition {
        if (dataExamples == null) dataExamples = Collections.emptyList();
        if (variants == null) variants = Collections.emptyList();
        if (publishTo == null) publishTo = Collections.emptyList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DataExample(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("data") JsonNode data
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VariantDefinition(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("attributes") Map<String, String> attributes,
            @JsonProperty("templateModel") JsonNode templateModel
    ) {
        public VariantDefinition {
            if (attributes == null) attributes = Collections.emptyMap();
        }
    }
}
