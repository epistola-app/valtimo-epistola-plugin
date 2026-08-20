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

import app.epistola.valtimo.expression.CacheResultForEvaluation
import app.epistola.valtimo.expression.EpistolaExpressionFunction
import app.epistola.valtimo.expression.ExpressionContext
import app.epistola.valtimo.expression.ExpressionFunctionResultSchema
import org.springframework.stereotype.Component

/**
 * Test-app example of a host-defined JSONata function with schema-backed suggestions.
 */
@Component
class GetCustomDataFunction : EpistolaExpressionFunction {
    override fun name() = "getCustomData"

    override fun description() = "Returns example custom data for manual expression-editor testing"

    @CacheResultForEvaluation
    @ExpressionFunctionResultSchema("expression-schemas/get-custom-data.schema.json")
    fun execute(
        @Suppress("UNUSED_PARAMETER") context: ExpressionContext,
    ): Map<String, Any> =
        mapOf(
            "customerName" to "Example customer",
            "status" to "active",
            "tags" to listOf("priority", "digital"),
        )
}