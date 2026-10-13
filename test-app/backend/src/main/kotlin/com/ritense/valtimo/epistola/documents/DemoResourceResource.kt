// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.documents

import com.ritense.resource.service.ResourceService
import com.ritense.resource.web.ObjectUrlDTO
import com.ritense.resource.web.ResourceDTO
import com.ritense.resource.web.rest.ResourceResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * Serves a stored letter on the path the case UI already calls.
 *
 * Valtimo's own `LocalResource` implements this interface by returning `204 No Content`, so a case
 * worker clicking a file in an installation without the Documenten API or S3 gets nothing. The bean
 * is `@ConditionalOnMissingBean`, so this replaces it; the UI is unchanged, because it already asks
 * for `GET /api/v1/resource/{id}/download` — the mapping lives on {@link ResourceResource} and is
 * inherited, not redeclared here.
 *
 * Authorization is Valtimo's: the resource endpoints sit behind the same security configuration as
 * before. This adds no new path.
 */
class DemoResourceResource(
    private val resourceService: ResourceService,
) : ResourceResource {
    override fun get(resourceId: String): ResponseEntity<ObjectUrlDTO> =
        ResponseEntity.ok(resourceService.getResourceUrl(UUID.fromString(resourceId)))

    override fun getContent(resourceId: String): ResponseEntity<ByteArray> {
        val content = resourceService.getResourceContent(UUID.fromString(resourceId))
        val resource = content.resource
        return ResponseEntity
            .ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                // `attachment` so a letter downloads rather than replacing the case the worker is
                // looking at. The name is the one stored, which is what they expect to see saved.
                "attachment; filename=\"${resource?.name ?: resourceId}\"",
            ).contentType(contentTypeOf(resource))
            .body(content.content)
    }

    override fun delete(resourceId: String): ResponseEntity<Void> {
        resourceService.removeResource(resourceId)
        return ResponseEntity.noContent().build()
    }

    override fun register(resourceDTO: ResourceDTO): ResponseEntity<ResourceDTO> =
        ResponseEntity.ok(resourceService.registerResource(resourceDTO))

    /**
     * Derived from the extension rather than stored alongside, because that is all a
     * [ResourceDTO] carries. Letters are PDFs; anything else downloads as bytes.
     */
    private fun contentTypeOf(resource: ResourceDTO?): MediaType =
        when (resource?.extension?.lowercase()) {
            "pdf" -> MediaType.APPLICATION_PDF
            "json" -> MediaType.APPLICATION_JSON
            "txt" -> MediaType.TEXT_PLAIN
            else -> MediaType.APPLICATION_OCTET_STREAM
        }
}