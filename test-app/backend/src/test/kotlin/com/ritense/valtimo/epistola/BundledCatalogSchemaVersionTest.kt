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
package com.ritense.valtimo.epistola

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Guards the bundled classpath catalogs against catalog wire-schema drift.
 *
 * Epistola Suite rejects any catalog import whose wire `schemaVersion` is below its baseline
 * (`catalog-schema-too-old`, HTTP 400). When the suite raises that baseline, the bundled
 * catalogs must be re-authored to match — this test fails loudly in CI if a bundled manifest
 * or resource detail drifts below the version this plugin build targets, rather than only
 * failing in production at import time (see GitHub issue #71).
 *
 * Bump [EXPECTED_CATALOG_SCHEMA_VERSION] together with the catalog files when targeting a
 * newer suite baseline.
 */
class BundledCatalogSchemaVersionTest {
    private val mapper = ObjectMapper()
    private val resolver = PathMatchingResourcePatternResolver()

    @Test
    fun `every bundled catalog manifest is at the current wire schema`() {
        val manifests = resolver.getResources("classpath*:config/epistola/catalogs/*/catalog.json")
        assertThat(manifests)
            .describedAs("expected at least one bundled catalog manifest on the classpath")
            .isNotEmpty

        manifests.forEach { resource ->
            val schemaVersion =
                resource.inputStream
                    .use { mapper.readTree(it) }
                    .path("schemaVersion")
                    .asInt(-1)
            assertThat(schemaVersion)
                .describedAs("schemaVersion of bundled manifest %s", resource.description)
                .isEqualTo(EXPECTED_CATALOG_SCHEMA_VERSION)
        }
    }

    @Test
    fun `every bundled catalog resource detail is at the current wire schema`() {
        val details = resolver.getResources("classpath*:config/epistola/catalogs/*/resources/**/*.json")
        assertThat(details)
            .describedAs("expected bundled catalog resource detail files on the classpath")
            .isNotEmpty

        details.forEach { resource ->
            val schemaVersion =
                resource.inputStream
                    .use { mapper.readTree(it) }
                    .path("schemaVersion")
                    .asInt(-1)
            assertThat(schemaVersion)
                .describedAs("schemaVersion of bundled resource detail %s", resource.description)
                .isEqualTo(EXPECTED_CATALOG_SCHEMA_VERSION)
        }
    }

    @Test
    fun `every bundled template document uses a synthetic root node`() {
        val details = resolver.getResources("classpath*:config/epistola/catalogs/*/resources/template/*.json")
        assertThat(details)
            .describedAs("expected bundled template resource detail files on the classpath")
            .isNotEmpty

        details.forEach { resource ->
            val templateModel =
                resource.inputStream
                    .use { mapper.readTree(it) }
                    .path("resource")
                    .path("templateModel")
            assertThat(templateModel.isMissingNode)
                .describedAs("templateModel of bundled template %s", resource.description)
                .isFalse

            val rootId = templateModel.path("root").asText("")
            val nodes = templateModel.path("nodes")
            assertThat(nodes.path(rootId).path("type").asText(""))
                .describedAs("root node type of bundled template %s", resource.description)
                .isEqualTo("root")
            assertThat(nodes.count { it.path("type").asText("") == "root" })
                .describedAs("root node count of bundled template %s", resource.description)
                .isEqualTo(1)
        }
    }

    companion object {
        /** The catalog wire `schemaVersion` this plugin build targets (Epistola Suite >= 0.26.0). */
        private const val EXPECTED_CATALOG_SCHEMA_VERSION = 4
    }
}