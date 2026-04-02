package com.ritense.valtimo.epistola.plugin;

import app.epistola.valtimo.service.DataMappingResolverService;
import app.epistola.valtimo.service.EpistolaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.valueresolver.ValueResolverService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin.NormalizedAttribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link EpistolaPlugin#normalizeVariantAttributes(Object)},
 * covering backward compatibility with the old Map format and the new List format.
 */
class NormalizeVariantAttributesTest {

    private EpistolaPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new EpistolaPlugin(
                mock(EpistolaService.class),
                mock(ValueResolverService.class),
                mock(ObjectMapper.class),
                mock(DataMappingResolverService.class)
        );
    }

    @Test
    void nullInput_returnsEmptyList() {
        var result = plugin.normalizeVariantAttributes(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void oldFormat_mapOfStrings_convertsToNormalizedAttributes() {
        Map<String, String> oldFormat = new LinkedHashMap<>();
        oldFormat.put("language", "dutch");
        oldFormat.put("brand", "acme");

        var result = plugin.normalizeVariantAttributes(oldFormat);

        assertEquals(2, result.size());
        assertEquals(new NormalizedAttribute("language", "dutch", null), result.get(0));
        assertEquals(new NormalizedAttribute("brand", "acme", null), result.get(1));
    }

    @Test
    void oldFormat_emptyValues_areFiltered() {
        Map<String, String> oldFormat = new LinkedHashMap<>();
        oldFormat.put("language", "dutch");
        oldFormat.put("", "ignored");
        oldFormat.put("empty", "");

        var result = plugin.normalizeVariantAttributes(oldFormat);

        assertEquals(1, result.size());
        assertEquals("language", result.get(0).key());
    }

    @Test
    void newFormat_listOfMaps_convertsWithRequiredFlag() {
        List<Map<String, Object>> newFormat = List.of(
                Map.of("key", "language", "value", "dutch", "required", true),
                Map.of("key", "brand", "value", "acme", "required", false)
        );

        var result = plugin.normalizeVariantAttributes(newFormat);

        assertEquals(2, result.size());
        assertEquals(new NormalizedAttribute("language", "dutch", true), result.get(0));
        assertEquals(new NormalizedAttribute("brand", "acme", false), result.get(1));
    }

    @Test
    void newFormat_missingRequiredField_defaultsToNull() {
        List<Map<String, Object>> newFormat = List.of(
                Map.of("key", "language", "value", "dutch")
        );

        var result = plugin.normalizeVariantAttributes(newFormat);

        assertEquals(1, result.size());
        assertNull(result.get(0).required());
    }

    @Test
    void newFormat_emptyEntries_areFiltered() {
        List<Map<String, Object>> newFormat = List.of(
                Map.of("key", "language", "value", "dutch", "required", true),
                Map.of("key", "", "value", "ignored", "required", true),
                Map.of("key", "empty", "value", "", "required", false)
        );

        var result = plugin.normalizeVariantAttributes(newFormat);

        assertEquals(1, result.size());
        assertEquals("language", result.get(0).key());
    }

    @Test
    void emptyMap_returnsEmptyList() {
        var result = plugin.normalizeVariantAttributes(Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyList_returnsEmptyList() {
        var result = plugin.normalizeVariantAttributes(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void unexpectedType_returnsEmptyList() {
        var result = plugin.normalizeVariantAttributes("not a map or list");
        assertTrue(result.isEmpty());
    }
}
