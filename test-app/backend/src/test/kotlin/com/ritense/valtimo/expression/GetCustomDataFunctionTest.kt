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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package com.ritense.valtimo.expression

import app.epistola.valtimo.expression.ExpressionContext
import app.epistola.valtimo.expression.ExpressionFunctionRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class GetCustomDataFunctionTest {
    private val function = GetCustomDataFunction()
    private val objectMapper = ObjectMapper()

    @Test
    fun `returns representative custom data`() {
        val result = function.execute(mock<ExpressionContext>())

        assertThat(result)
            .containsEntry("customerName", "Example customer")
            .containsEntry("status", "active")
        assertThat(result["tags"]).isEqualTo(listOf("priority", "digital"))
    }

    @Test
    fun `publishes its result schema through the expression registry`() {
        val info = ExpressionFunctionRegistry(listOf(function)).listFunctions().single()
        val overload = info.overloads().single()

        assertThat(info.name()).isEqualTo("getCustomData")
        assertThat(overload.schemaDiagnostic()).isNull()
        assertThat(
            overload
                .resultSchema()
                .path("properties")
                .path("tags")
                .path("type")
                .asText(),
        ).isEqualTo("array")
        assertThat(
            overload
                .resultSchema()
                .path("properties")
                .path("tags")
                .path("items")
                .path("type")
                .asText(),
        ).isEqualTo("string")
    }

    @Test
    fun `runtime result conforms to its published schema`() {
        val overload =
            ExpressionFunctionRegistry(listOf(function))
                .listFunctions()
                .single()
                .overloads()
                .single()
        val schema =
            SchemaRegistry
                .withDefaultDialect(Dialects.getDraft202012())
                .getSchema(overload.resultSchema())
        val runtimeResult = function.execute(mock<ExpressionContext>())

        assertThat(schema.validate(objectMapper.valueToTree(runtimeResult))).isEmpty()
    }
}