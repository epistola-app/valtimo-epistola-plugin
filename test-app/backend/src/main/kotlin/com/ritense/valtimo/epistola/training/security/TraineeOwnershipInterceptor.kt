// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * Enforces per-trainee ownership on the endpoints that [TrainingHttpSecurityConfigurer] widened
 * past their flat `ROLE_ADMIN` gate — for the requests identifiable purely from the URL (path
 * variables / query params). POST/PUT bodies are covered by [TraineeOwnershipRequestBodyAdvice]
 * (a `HandlerInterceptor` runs before Spring MVC has bound the request body), list responses by
 * [TraineeOwnershipResponseBodyAdvice].
 *
 * Covers process-link, plugin-configuration, and the case-definition management surface
 * (`CaseHttpSecurityConfigurer`/`InternalCaseHttpSecurityConfigurer` — tabs, settings, list
 * columns, widget/header tabs, startable items, export, internal status). See
 * [TrainingHttpSecurityConfigurer]'s KDoc for what's still explicitly excluded from that surface.
 *
 * **Also covers the case/document/task data plane** — added after discovering that granting
 * trainees real `ROLE_ADMIN` (see [com.ritense.valtimo.epistola.training.TraineeKeys.ADMIN_AUTHORITY]'s
 * KDoc) doesn't just widen the admin-configuration surface above: `all.permission.json` grants
 * `ROLE_ADMIN` unconditioned (no per-tenant scoping at all) access to `JsonSchemaDocument` and
 * `OperatonTask`, among other resource types, and PBAC unions grants across every role a principal
 * carries. Confirmed live, not assumed: a trainee could fetch a document and a task belonging to a
 * completely unrelated demo case type, full content included. Unlike the config-management
 * surface, `document`/`task` identify themselves purely by their own id (never a document-/
 * case-definition name directly), so [DocumentOwnershipResolver]/[TaskOwnershipResolver] resolve
 * that first. Only the document/task endpoints this package's own manual testing found reachable
 * are covered here (view, delete/complete/assign/unassign/set-due-date, search) — see
 * [TraineeAdminSurfaceGuardFilter]'s KDoc-equivalent gap note for the other unconditioned
 * `ROLE_ADMIN` resource types (`Note`, `JsonSchemaDocumentSnapshot`, `Dashboard`, `CaseTab`,
 * `SearchField`, `Object`, `ResourcePermission`) still open.
 */
class TraineeOwnershipInterceptor(
    private val ownershipChecks: TraineeOwnershipChecks,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val traineeIdentity = ownershipChecks.currentTraineeIdentityOrNull() ?: return true

        pathVariable(request, "pluginConfigurationId")?.let { pluginConfigurationId ->
            return allowOrForbid(response, ownershipChecks.isOwnPluginConfiguration(traineeIdentity, pluginConfigurationId))
        }

        pathVariable(request, "processLinkId")?.let { processLinkId ->
            val processDefinitionId =
                runCatching { UUID.fromString(processLinkId) }
                    .getOrNull()
                    ?.let { ownershipChecks.resolveProcessDefinitionIdOfProcessLink(it) }
            val owned = processDefinitionId != null && ownershipChecks.isOwnProcessDefinition(traineeIdentity, processDefinitionId)
            return allowOrForbid(response, owned)
        }

        request.getParameter("processDefinitionId")?.let { processDefinitionId ->
            val readOnly = request.method.equals("GET", ignoreCase = true)
            return allowOrForbid(
                response,
                ownershipChecks.isOwnProcessDefinition(traineeIdentity, processDefinitionId, allowShared = readOnly),
            )
        }

        // Every endpoint in Valtimo's case-definition management surface is path-scoped directly
        // by the case key — just under different variable names per endpoint (verified against
        // Valtimo 13.44.0 source: CaseDefinitionResource/CaseTabManagementResource/etc. call the
        // same path segment caseDefinitionKey, caseDefinitionName, or bare key depending on the
        // endpoint, never more than one per request).
        caseDefinitionKeyPathVariable(request)?.let { caseDefinitionKey ->
            val readOnly = request.method.equals("GET", ignoreCase = true)
            return allowOrForbid(
                response,
                ownershipChecks.isOwnCaseDefinition(traineeIdentity, caseDefinitionKey, allowShared = readOnly),
            )
        }

        // JsonSchemaDocumentResource: GET/DELETE /api/v1/document/{id} — {id} is a generic name,
        // only safe to key off because TrainingWebConfig registers this interceptor against
        // /api/v1/document/** specifically, so it only ever fires for these endpoints' own {id}.
        //
        // Deliberately no allowShared here, unlike the case-definition-management checks above:
        // "form-flow-demo" is a live, shared case type real staff/other tests can create genuine
        // case instances under — allowShared there is about sharing read-only *structure*
        // (settings/tabs/schema), never actual case *data*. demo.permission.json already drew this
        // exact line before the ROLE_ADMIN pivot: JsonSchemaDocumentDefinition:view has a
        // "form-flow-demo"-conditioned grant, JsonSchemaDocument:view does not — only
        // ${currentUserId}. Found by re-checking after the fix looked "mostly right": a trainee's
        // task list still showed several real tasks, all belonging to form-flow-demo document
        // instances that were not their own.
        pathVariable(request, "id")?.let { documentId ->
            return allowOrForbid(response, ownershipChecks.isOwnDocument(traineeIdentity, documentId))
        }

        // JsonSchemaDocumentSearchResource: POST /api/v1/document-definition/{name}/search — the
        // definition name is directly in the URL, no resolution needed, same as caseDefinitionKey
        // above; deliberately checked separately (not folded into caseDefinitionKeyPathVariable)
        // since "name" is generic enough that reusing that shared helper risks matching an
        // unrelated variable on some other already-registered path. No allowShared, same reasoning
        // as the document id check above.
        pathVariable(request, "name")?.let { documentDefinitionName ->
            return allowOrForbid(response, ownershipChecks.isOwnCaseDefinition(traineeIdentity, documentDefinitionName))
        }

        // TaskResource: GET (view) / POST assign|unassign|complete|set-due-date /api/v1|v2/task/{taskId}.
        // No allowShared, same reasoning as the document id check above.
        pathVariable(request, "taskId")?.let { taskId ->
            return allowOrForbid(response, ownershipChecks.isOwnTask(traineeIdentity, taskId))
        }

        return true
    }

    private fun caseDefinitionKeyPathVariable(request: HttpServletRequest): String? =
        pathVariable(request, "caseDefinitionKey")
            ?: pathVariable(request, "caseDefinitionName")
            ?: pathVariable(request, "key")

    private fun allowOrForbid(
        response: HttpServletResponse,
        allowed: Boolean,
    ): Boolean {
        if (!allowed) {
            // Not plain sendError(403, "Not your dossier") — see TraineeRejection.kt's KDoc for
            // why that message never actually reached the client.
            response.rejectAsForbidden("This belongs to another user's dossier, not yours.")
        }
        return allowed
    }

    private fun pathVariable(
        request: HttpServletRequest,
        name: String,
    ): String? {
        @Suppress("UNCHECKED_CAST")
        val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        return variables?.get(name)
    }
}