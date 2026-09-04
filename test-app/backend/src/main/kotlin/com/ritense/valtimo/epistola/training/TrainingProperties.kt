// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `epistola.training.*` — only read when the `training` Spring profile is active (see
 * [TrainingConfiguration]).
 */
@ConfigurationProperties(prefix = "epistola.training")
data class TrainingProperties(
    /** Case-/document-definition key of the case cloned into every trainee's dossier. */
    val templateCaseDefinitionKey: String = "form-flow-demo",
    val templateCaseDefinitionVersionTag: String = "1.0.0",
)