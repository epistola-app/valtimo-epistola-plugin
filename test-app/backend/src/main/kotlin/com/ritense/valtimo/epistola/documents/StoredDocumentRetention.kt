// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.documents

import app.epistola.valtimo.logging.Failures
import com.ritense.authorization.AuthorizationContext.Companion.runWithoutAuthorization
import com.ritense.document.domain.impl.JsonSchemaDocumentId
import com.ritense.document.service.DocumentService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Throws away letters the demo has kept long enough.
 *
 * A demo generates letters forever and nobody prunes them, so the table grows without bound and
 * every PDF in it is somebody's name and address. Thirty days is long enough to show a dossier with
 * its correspondence and short enough that the database stays a demo database.
 *
 * **Detaches before deleting.** A case document carries the file as a related file, so removing only
 * the row would leave a dossier listing a letter that no longer opens — worse than not keeping it.
 * Rows written before the case id was recorded cannot be detached; they are still deleted, because
 * leaving personal data behind to protect a dangling link in a demo would be the wrong trade, and a
 * dossier old enough to have one is older than the retention window anyway.
 *
 * Demo-only, like the storage it prunes: a real installation keeps its documents in the Documenten
 * API, under whatever retention the organisation decided.
 *
 * `open` because `@Transactional` is proxied, and a Kotlin class is final unless it says otherwise.
 * [DemoResourceService] needs no such thing only because it implements an interface, so Spring can
 * proxy it without subclassing — the context failed to start until this said `open`.
 */
open class StoredDocumentRetention(
    private val repository: StoredDocumentRepository,
    private val documentService: DocumentService,
    private val clock: Clock,
    private val retentionDays: Long,
) {
    /**
     * Runs on a cron rather than a fixed delay so that a restart cannot reset the clock, and at
     * night because deleting a letter while somebody is reading the dossier it belongs to is a poor
     * demo. Every node would run it; one demo has one node, and a second deletion finds nothing.
     */
    @Scheduled(cron = "\${epistola.demo.documents.deletion-cron:0 30 3 * * *}")
    @Transactional
    open fun deleteExpired() {
        val cutoff = LocalDateTime.now(clock).minusDays(retentionDays)
        val expired = repository.findAllByCreatedOnBefore(cutoff)
        if (expired.isEmpty()) {
            logger.debug { "No stored letters older than $retentionDays days" }
            return
        }

        var detached = 0
        for (document in expired) {
            if (detach(document)) {
                detached++
            }
        }
        repository.deleteAll(expired)

        // Counts and ids only: a letter's file name can carry a case reference, and its content is
        // somebody's correspondence. See the logging rule in CLAUDE.md.
        logger.info {
            "Deleted ${expired.size} stored letter(s) older than $retentionDays days, " +
                "$detached of them detached from their case"
        }
    }

    /** Whether the letter was taken off its case; false when no case was recorded, or it is gone. */
    private fun detach(document: StoredDocument): Boolean {
        val caseId = document.caseDocumentId ?: return false
        return runCatching {
            runWithoutAuthorization {
                documentService.removeRelatedFile(
                    JsonSchemaDocumentId.existingId(UUID.fromString(caseId)),
                    UUID.fromString(document.id),
                )
            }
            true
        }.getOrElse { failure ->
            // A case deleted before its letters, or a related file already removed by hand. The row
            // still goes: a demo that cannot prune is a demo that fills its disk.
            logger.debug { "Could not detach stored letter ${document.id} (${Failures.describe(failure)})" }
            false
        }
    }
}