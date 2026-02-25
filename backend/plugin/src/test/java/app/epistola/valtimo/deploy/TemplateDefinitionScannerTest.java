package app.epistola.valtimo.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TemplateDefinitionScanner.
 * <p>
 * Uses test fixture files in test/resources/config/epistola/templates/:
 * - test-valid/definition.json — valid template
 * - test-no-slug/definition.json — missing slug (should be skipped)
 * - test-no-version/definition.json — missing version (should be skipped)
 */
class TemplateDefinitionScannerTest {

    private TemplateDefinitionScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new TemplateDefinitionScanner(new ObjectMapper());
    }

    @Test
    void scanTemplateDefinitions_findsValidTemplatesOnClasspath() {
        List<TemplateDefinition> definitions = scanner.scanTemplateDefinitions();

        assertFalse(definitions.isEmpty(), "Should find at least one template on classpath");

        // Should contain the valid test fixture
        TemplateDefinition valid = definitions.stream()
                .filter(d -> "test-valid".equals(d.slug()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should find 'test-valid' template"));

        assertEquals("Test Template", valid.name());
        assertEquals("1.0.0", valid.version());
        assertNotNull(valid.dataModel());
        assertNotNull(valid.templateModel());
        assertFalse(valid.dataExamples().isEmpty());
        assertEquals("ex-1", valid.dataExamples().get(0).id());
        assertEquals(List.of("production"), valid.publishTo());
    }

    @Test
    void scanTemplateDefinitions_skipsTemplateWithoutSlug() {
        List<TemplateDefinition> definitions = scanner.scanTemplateDefinitions();

        boolean hasNoSlug = definitions.stream()
                .anyMatch(d -> "No Slug Template".equals(d.name()));

        assertFalse(hasNoSlug, "Template without slug should be skipped");
    }

    @Test
    void scanTemplateDefinitions_skipsTemplateWithoutVersion() {
        List<TemplateDefinition> definitions = scanner.scanTemplateDefinitions();

        boolean hasNoVersion = definitions.stream()
                .anyMatch(d -> "test-no-version".equals(d.slug()));

        assertFalse(hasNoVersion, "Template without version should be skipped");
    }

    @Test
    void scanTemplateDefinitions_parsesDataModelCorrectly() {
        List<TemplateDefinition> definitions = scanner.scanTemplateDefinitions();

        TemplateDefinition valid = definitions.stream()
                .filter(d -> "test-valid".equals(d.slug()))
                .findFirst()
                .orElseThrow();

        // Data model should be a JSON Schema object
        assertEquals("object", valid.dataModel().get("type").asText());
        assertTrue(valid.dataModel().has("properties"));
        assertTrue(valid.dataModel().get("properties").has("name"));
    }

    @Test
    void scanTemplateDefinitions_defaultsEmptyCollectionsWhenMissing() {
        // The test-valid template has explicit empty variants list
        List<TemplateDefinition> definitions = scanner.scanTemplateDefinitions();

        TemplateDefinition valid = definitions.stream()
                .filter(d -> "test-valid".equals(d.slug()))
                .findFirst()
                .orElseThrow();

        assertNotNull(valid.variants());
        assertTrue(valid.variants().isEmpty());
    }
}
