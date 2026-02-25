package app.epistola.valtimo.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scans the classpath for Epistola template definitions.
 * <p>
 * Template definitions are expected at:
 * {@code config/epistola/templates/{slug}/definition.json}
 */
@Slf4j
public class TemplateDefinitionScanner {

    private static final String TEMPLATE_PATTERN = "classpath*:config/epistola/templates/*/definition.json";

    private final ObjectMapper objectMapper;

    public TemplateDefinitionScanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Scan classpath for template definition files and parse them.
     *
     * @return List of parsed template definitions (never null)
     */
    public List<TemplateDefinition> scanTemplateDefinitions() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        Resource[] resources;
        try {
            resources = resolver.getResources(TEMPLATE_PATTERN);
        } catch (IOException e) {
            log.warn("Failed to scan for template definitions: {}", e.getMessage());
            return Collections.emptyList();
        }

        List<TemplateDefinition> definitions = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                TemplateDefinition definition = objectMapper.readValue(is, TemplateDefinition.class);
                if (definition.slug() == null || definition.slug().isBlank()) {
                    log.warn("Skipping template definition without slug: {}", resource.getDescription());
                    continue;
                }
                if (definition.version() == null || definition.version().isBlank()) {
                    log.warn("Skipping template definition without version: {} (slug={})",
                            resource.getDescription(), definition.slug());
                    continue;
                }
                definitions.add(definition);
                log.debug("Found template definition: slug={}, version={}", definition.slug(), definition.version());
            } catch (IOException e) {
                log.warn("Failed to parse template definition from {}: {}", resource.getDescription(), e.getMessage());
            }
        }

        log.info("Found {} template definitions on classpath", definitions.size());
        return definitions;
    }
}
