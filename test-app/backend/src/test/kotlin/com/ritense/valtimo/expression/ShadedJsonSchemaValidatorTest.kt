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

import app.epistola.valtimo.expression.EpistolaExpressionFunction
import app.epistola.valtimo.expression.ExpressionContext
import app.epistola.valtimo.expression.ExpressionFunctionResultSchema
import app.epistola.valtimo.expression.ExpressionFunctionSchemaResolver
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.ResourceBundle

/**
 * The plugin shades json-schema-validator 2.x, while Valtimo (13.47+) ships 1.x unshaded. Both
 * copies must work on the one application classpath. This runs against the real test-app
 * classpath, where the plugin arrives as its shaded jar next to Valtimo's own validator.
 */
class ShadedJsonSchemaValidatorTest {
    @Test
    fun `leaves the host's own json-schema-validator in place`() {
        // Valtimo's external-plugin module needs the 1.x API; 2.x removed this class.
        Class.forName("com.networknt.schema.ValidationMessage")
    }

    @Test
    fun `ships its message bundle under a name the host's copy does not share`() {
        // Both copies otherwise load "jsv-messages", and whichever comes first on the classpath
        // wins; 1.x messages carry a "{0}: " prefix 2.x no longer expects and lack 2.x-only keys.
        val bundle = ResourceBundle.getBundle("epistola-shaded-jsv-messages")

        assertThat(bundle.containsKey("discriminator.oneOf.no_match_found")).isTrue()
        assertThat(bundle.getString("type")).doesNotStartWith("{0}:")
    }

    @Test
    fun `validates result schemas with the shaded copy, including its error messages`() {
        val resolver = ExpressionFunctionSchemaResolver(ObjectMapper())
        val method = InvalidSchemaFunction::class.java.getMethod("execute", ExpressionContext::class.java)

        val result = resolver.resolve(InvalidSchemaFunction(), method)

        assertThat(result.schema()).isNull()
        assertThat(result.diagnostic().code()).isEqualTo("INVALID_JSON_SCHEMA")
        // Formatted from the shaded message bundle, not the host's 1.x one of the same name.
        assertThat(result.diagnostic().message()).contains("/type: ").doesNotContain("{0}")
    }

    class InvalidSchemaFunction : EpistolaExpressionFunction {
        override fun name() = "invalidSchema"

        override fun description() = "Declares a result schema that is not valid JSON Schema"

        @ExpressionFunctionResultSchema("expression-schemas/invalid.schema.json")
        fun execute(
            @Suppress("UNUSED_PARAMETER") context: ExpressionContext,
        ): Map<String, Any> = emptyMap()
    }
}