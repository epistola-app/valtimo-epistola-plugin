// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import com.ritense.authorization.AuthorizationContext
import com.ritense.document.domain.Document
import com.ritense.document.service.DocumentService
import java.util.concurrent.Callable

/**
 * Resolves facts about a document instance that its REST endpoints don't carry in the URL.
 *
 * Needed because `JsonSchemaDocumentResource` identifies a document purely by its own id (a
 * UUID) — the document-definition name (== the case-definition key this training package scopes
 * configuration by) and the creator (who a trainee's *data* access is scoped by) are only
 * reachable by loading the document itself.
 *
 * Loaded without authorization: this answers "who owns this document", which must give the true
 * answer even for a document the caller may not see. The caller decides what to do with it.
 */
class DocumentOwnershipResolver(
    private val documentService: DocumentService,
) {
    fun resolveCaseDefinitionKey(documentId: String): String? = load(documentId)?.definitionId()?.name()

    /** `JsonSchemaDocument.createdBy` — Valtimo stamps it from `SecurityUtils.getCurrentUserLogin()` on creation. */
    fun resolveCreatedBy(documentId: String): String? = load(documentId)?.createdBy()

    private fun load(documentId: String): Document? =
        runCatching { AuthorizationContext.runWithoutAuthorization(Callable { documentService.get(documentId) }) }.getOrNull()
}