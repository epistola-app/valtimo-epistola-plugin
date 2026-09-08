// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.document.service.DocumentService

/**
 * Resolves which case-/document-definition a document instance belongs to.
 *
 * Needed because `JsonSchemaDocumentResource` identifies a document purely by its own id (a
 * UUID) — the document-definition name (== the case-definition key this training package already
 * scopes by everywhere else) is never in the URL, only reachable by loading the document itself.
 */
class DocumentOwnershipResolver(
    private val documentService: DocumentService,
) {
    fun resolveCaseDefinitionKey(documentId: String): String? =
        runCatching { documentService.get(documentId).definitionId().name() }.getOrNull()
}