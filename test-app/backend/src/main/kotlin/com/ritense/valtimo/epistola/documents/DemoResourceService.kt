// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.documents

import com.ritense.resource.service.ResourceService
import com.ritense.resource.service.request.FileUploadRequest
import com.ritense.resource.web.ObjectContentDTO
import com.ritense.resource.web.ObjectUrlDTO
import com.ritense.resource.web.ResourceDTO
import com.ritense.valtimo.contract.resource.FileStatus
import com.ritense.valtimo.contract.resource.Resource
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI
import java.net.URL
import java.time.LocalDateTime
import java.util.UUID

/**
 * Keeps a file in the demo's own table, so a case worker can open it later.
 *
 * Replaces Valtimo's `LocalResourceService`, which is a placeholder: its `store` throws
 * `NotImplementedError` and `LocalResource`'s download endpoint answers 204. Both beans are
 * `@ConditionalOnMissingBean`, so providing these takes over cleanly rather than competing. An
 * installation with the Documenten API or S3 configured keeps using that instead; this exists
 * because the demo has neither and a generated letter should not vanish when a process ends.
 *
 * **Why the demo and not the plugin.** The plugin owns no schema and needs no database changes to
 * install, which is what lets an operator add and remove it freely. Durable file storage is a host
 * concern, and bytes in Postgres are fine for demo letters and a poor idea at volume.
 *
 * Only what the case UI exercises is implemented. The rest refuses in a way that names this class,
 * so a future caller gets a straight answer instead of a silent empty result — the failure mode
 * Valtimo's own placeholder has.
 */
@Transactional
class DemoResourceService(
    private val repository: StoredDocumentRepository,
    /**
     * Where this application answers, from Valtimo's own `valtimo.app.scheme` / `valtimo.app.hostname`.
     *
     * Only used to fill the `url` field Valtimo's resource DTOs carry. The case UI downloads from
     * the path (`/api/v1/resource/{id}/download`) and never reads that field, but a `java.net.URL`
     * cannot be relative, so it needs an origin from somewhere rather than a hardcoded one.
     */
    private val baseUrl: String,
) : ResourceService {
    /**
     * Stores the bytes and returns the handle Valtimo hands around.
     *
     * The `key` is ignored on purpose: Valtimo's S3 implementation uses it as an object key, and
     * here the primary key is the resource id the case UI will ask for.
     */
    override fun store(
        key: String,
        fileUploadRequest: FileUploadRequest,
    ): Resource = store(key, fileUploadRequest, FileStatus.PENDING)

    override fun store(
        key: String,
        fileUploadRequest: FileUploadRequest,
        fileStatus: FileStatus,
    ): Resource {
        // Declared as functions on a Kotlin interface, so they are called as functions rather than
        // read as properties.
        val content = fileUploadRequest.getInputStream().use { it.readBytes() }
        val name =
            listOfNotNull(
                fileUploadRequest.getName().ifBlank { null },
                fileUploadRequest.getExtension().ifBlank { null },
            ).joinToString(".")
        return repository
            .save(
                StoredDocument(
                    id = UUID.randomUUID().toString(),
                    fileName = name.ifBlank { "document.pdf" },
                    contentType = fileUploadRequest.getContentType(),
                    sizeInBytes = content.size.toLong(),
                    content = content,
                    createdOn = LocalDateTime.now(),
                    // The delegate passes the case document id as the key, which S3 would use as an
                    // object key. Recording it is what lets retention detach the file from its case
                    // instead of leaving a dossier pointing at something that no longer opens.
                    caseDocumentId = key.takeIf { runCatching { UUID.fromString(it) }.isSuccess },
                ),
            ).asResource()
    }

    /**
     * The multipart overloads refuse, on purpose.
     *
     * The interface declares them, so they have to exist; nothing calls them. A generated letter
     * arrives as a [FileUploadRequest] from the delegate that copies it out of temporary storage,
     * and a person uploading a file in the UI never reaches here at all — Valtimo posts that to
     * `/v2/zaken-api/document/{id}/files`, the Zaken API, which this demo does not run.
     *
     * Implementing them would quietly turn the demo's table into a general file store. Keeping the
     * demo to *what a process generated* is the intent, so it is stated here rather than left to
     * whoever next reads the table and wonders what else can land in it.
     */
    override fun store(
        documentDefinitionName: String,
        multipartFile: MultipartFile,
    ): Resource = refuseUpload()

    override fun store(
        documentDefinitionName: String,
        multipartFile: MultipartFile,
        fileStatus: FileStatus,
    ): Resource = refuseUpload()

    override fun store(
        key: String,
        name: String,
        multipartFile: MultipartFile,
    ): Resource = refuseUpload()

    private fun refuseUpload(): Nothing =
        throw UnsupportedOperationException(
            "The demo keeps only documents a process generated. Uploading files needs the Documenten " +
                "API or S3 configured, which this application does not run.",
        )

    @Transactional(readOnly = true)
    override fun getResource(id: UUID): Resource = require(id).asResource()

    @Transactional(readOnly = true)
    override fun getResourceContent(id: UUID): ObjectContentDTO {
        val stored = require(id)
        return ObjectContentDTO(downloadUrl(id), stored.asDto(), stored.content)
    }

    @Transactional(readOnly = true)
    override fun getResourceUrl(id: UUID): ObjectUrlDTO = ObjectUrlDTO(downloadUrl(id), require(id).asDto())

    @Transactional(readOnly = true)
    override fun getResourceUrl(resourceId: String): URL = downloadUrl(UUID.fromString(resourceId))

    override fun removeResource(id: UUID) {
        repository.deleteById(id.toString())
    }

    override fun removeResource(resourceId: String) {
        repository.deleteById(resourceId)
    }

    @Transactional(readOnly = true)
    override fun getResourceByKey(key: String): Resource = require(UUID.fromString(key)).asResource()

    /**
     * Pending and activate belong to the two-phase upload S3 needs: reserve a key, let the browser
     * upload to it, then mark it live. Storing here is a single transaction, so there is no
     * intermediate state to move between and nothing to do.
     */
    override fun activate(id: UUID) = Unit

    override fun pending(id: UUID) = Unit

    override fun registerResource(resourceDTO: ResourceDTO): ResourceDTO =
        throw UnsupportedOperationException(
            "DemoResourceService stores files itself; there is no externally managed resource to register",
        )

    private fun require(id: UUID): StoredDocument =
        repository.findById(id.toString()).orElseThrow {
            NoSuchElementException("No stored document $id")
        }

    /**
     * The download URL, on **the origin the browser is already talking to**.
     *
     * This matters more than it looks. Valtimo's own `DownloadService` attaches the access token
     * only when it recognises the URL as its own backend:
     *
     * ```js
     * if (url.startsWith(endpointUri) || url.startsWith(window.location.origin) || url.startsWith('/api/'))
     *   // if download url is on backend use angular to get the content so access token is used
     * ```
     *
     * Anything else it treats as a pre-signed URL and navigates to directly, which carries no
     * Authorization header. Built from `valtimo.app.hostname` this produced `http://localhost:8080/…`
     * while the browser was on `:4200`, so the check failed, the browser navigated, and a case
     * worker clicking their letter got a 403 with the file sitting in the table.
     *
     * Taking scheme and host from the current request keeps it on that same origin, whether the
     * browser reaches the API directly or through the dev server's proxy. [baseUrl] stays as the
     * fallback for callers with no request in scope, such as a BPMN job thread.
     */
    private fun downloadUrl(id: UUID): URL {
        val origin =
            runCatching { ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: baseUrl
        return URI.create("${origin.trimEnd('/')}/api/v1/resource/$id/download").toURL()
    }

    private fun StoredDocument.asDto(): ResourceDTO =
        ResourceDTO(
            id,
            id,
            fileName,
            fileName.substringAfterLast('.', ""),
            sizeInBytes,
            createdOn,
            content,
        )
}