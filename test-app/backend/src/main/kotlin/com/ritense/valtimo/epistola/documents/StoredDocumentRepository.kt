// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.documents

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface StoredDocumentRepository : JpaRepository<StoredDocument, String> {
    /**
     * Returned rather than deleted in one statement, because each one has to be detached from its
     * case first — see [StoredDocumentRetention].
     */
    fun findAllByCreatedOnBefore(cutoff: LocalDateTime): List<StoredDocument>
}