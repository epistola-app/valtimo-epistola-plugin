/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package app.epistola.valtimo.service.versioncheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published test cases for clients of releases.json, run against {@link LineLookup}.
 *
 * <p>{@code releases/releases.conformance.json} is a copy of
 * https://epistola.app/releases.conformance.json (website, src/lib/server/releases/). The website
 * only adds cases; copy the new file over when it does.
 */
class LineLookupConformanceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    @TestFactory
    Stream<DynamicTest> passesEveryPublishedCase() throws IOException {
        JsonNode conformance;
        try (InputStream in = getClass().getResourceAsStream("/releases/releases.conformance.json")) {
            assertThat(in).as("releases.conformance.json on the test classpath").isNotNull();
            conformance = MAPPER.readTree(in);
        }
        Map<String, EpistolaReleasesDocument.ProductReleases> products = new LinkedHashMap<>();
        conformance.get("products").fields().forEachRemaining(product -> products.put(
                product.getKey(),
                MAPPER.convertValue(product.getValue(), EpistolaReleasesDocument.ProductReleases.class)));

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode testCase : conformance.get("cases")) {
            tests.add(DynamicTest.dynamicTest(testCase.get("name").asText(), () -> {
                var result = LineLookup.evaluate(
                        products.get(testCase.get("product").asText()),
                        testCase.get("version").asText());
                JsonNode expect = testCase.get("expect");
                assertThat(actual(result)).isEqualTo(Map.of(
                        "status", expect.get("status").asText(),
                        "line", text(expect.get("line")),
                        "patch", text(expect.get("patch")),
                        "newerRelease", text(expect.get("newerRelease")),
                        "nextPrerelease", text(expect.get("nextPrerelease"))));
            }));
        }
        assertThat(tests).isNotEmpty();
        return tests.stream();
    }

    private static Map<String, String> actual(LineLookup.Result result) {
        return Map.of(
                "status", result.status().name().toLowerCase(Locale.ROOT),
                "line", String.valueOf(result.line()),
                "patch", version(result.patch()),
                "newerRelease", version(result.newerRelease()),
                "nextPrerelease", version(result.nextPrerelease()));
    }

    private static String version(EpistolaReleasesDocument.ReleaseRef ref) {
        return ref != null ? ref.version() : "null";
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "null" : node.asText();
    }
}
