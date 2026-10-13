// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.documents

import com.ritense.valtimo.contract.resource.Resource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * A generated letter, kept so a case worker can open it after the process has ended.
 *
 * Owned by the demo application. The plugin stores nothing of its own and needs no database changes
 * to install — an operator drops the jar in and takes it out again — and a real installation points
 * Valtimo at the Documenten API or S3. Valtimo's own `local-resource` module is a placeholder whose
 * `store` throws `NotImplementedError` and whose download endpoint answers 204, so an installation
 * with neither has nowhere to put a file. This is the smallest stand-in for one.
 */
@Entity
@Table(name = "epistola_demo_document")
class StoredDocument(
    @Id
    @Column(name = "epistola_demo_document_id", nullable = false, updatable = false)
    val id: String,
    @Column(name = "file_name", nullable = false)
    val fileName: String,
    @Column(name = "content_type", nullable = false)
    val contentType: String,
    @Column(name = "size_in_bytes", nullable = false)
    val sizeInBytes: Long,
    @Lob
    @Column(name = "content", nullable = false)
    val content: ByteArray,
    @Column(name = "created_on", nullable = false)
    val createdOn: LocalDateTime,
    /**
     * The case this letter belongs to, so retention can detach it before deleting it.
     *
     * Nullable because rows written before the column existed have no case recorded — see the
     * changeset. Retention deletes those too; it simply cannot tidy the case they were on.
     */
    @Column(name = "case_document_id")
    val caseDocumentId: String? = null,
) {
    /**
     * The shape Valtimo asks for.
     *
     * `extension` is split off the file name because that is what Valtimo's own resource DTOs
     * carry, and the case UI builds its download link and icon from it.
     */
    fun asResource(): Resource =
        object : Resource {
            override fun id(): UUID = UUID.fromString(this@StoredDocument.id)

            override fun name(): String = fileName

            override fun extension(): String = fileName.substringAfterLast('.', "")

            override fun sizeInBytes(): Long = this@StoredDocument.sizeInBytes

            override fun createdOn(): LocalDateTime = this@StoredDocument.createdOn
        }
}