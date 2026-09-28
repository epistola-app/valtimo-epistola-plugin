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
 * **Also covers the case/document/task data plane**, as a second line behind PBAC. Trainees no
 * longer carry `ROLE_ADMIN` server-side ([TraineeAdminAuthorityStripFilter]), so Valtimo's own PBAC
 * (`demo.permission.json`) already limits them to cases they created. These checks apply the same
 * rule ([TraineeOwnershipChecks.isOwnDocument]: the case document's `createdBy` is the caller) on
 * the document/task/process endpoints, which identify themselves only by their own id —
 * [DocumentOwnershipResolver]/[TaskOwnershipResolver]/[ProcessInstanceOwnershipResolver] resolve
 * that to the owning case document first. They were the only data-plane protection while trainees
 * carried an unconditioned `ROLE_ADMIN` (see docs/training-facility.md, "The critical finding").
 *
 * **Also covers what would otherwise be hard-blocked "arbitrary id" endpoints in
 * [TraineeAdminSurfaceGuardFilter]** - force-deleting a process instance and reconciling a stuck
 * Epistola execution both take a bare runtime id with nothing else to check ownership against, so
 * they were originally just blocked outright. [ProcessInstanceOwnershipResolver] made resolving
 * them to a case-definition key possible the same way as documents/tasks, so they're scoped here
 * instead - not every "arbitrary id" endpoint could be treated this way (see that filter's KDoc
 * for the ones that genuinely can't, or have no legitimate trainee use case at all).
 */
class TraineeOwnershipInterceptor(
    private val ownershipChecks: TraineeOwnershipChecks,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val trainee = ownershipChecks.currentTraineeOrNull() ?: return true
        val traineeIdentity = trainee.identity

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
        // Scoped by who created the case, not by its case type: a trainee works on their own cases
        // in a shared case type as much as in their own dossier, and on nobody else's anywhere.
        // Same rule as demo.permission.json gives PBAC — see TraineeOwnershipChecks.isOwnDocument.
        pathVariable(request, "id")?.let { documentId ->
            return allowOrForbid(response, ownershipChecks.isOwnDocument(trainee.login, documentId))
        }

        // JsonSchemaDocumentSearchResource: POST /api/v1/document-definition/{name}/search — the
        // definition name is directly in the URL, no resolution needed, same as caseDefinitionKey
        // above; deliberately checked separately (not folded into caseDefinitionKeyPathVariable)
        // since "name" is generic enough that reusing that shared helper risks matching an
        // unrelated variable on some other already-registered path. Shared case types are allowed:
        // the search itself is narrowed to the caller's own cases by PBAC
        // (JsonSchemaDocument:view_list, createdBy == ${currentUserEmail}), so paging and totals
        // stay right. Another trainee's dossier is still refused outright.
        pathVariable(request, "name")?.let { documentDefinitionName ->
            return allowOrForbid(
                response,
                ownershipChecks.isOwnCaseDefinition(traineeIdentity, documentDefinitionName, allowShared = true),
            )
        }

        // TaskResource: GET (view) / POST assign|unassign|complete|set-due-date /api/v1|v2/task/{taskId}.
        // Scoped by the task's case document's creator, same as the document id check above.
        pathVariable(request, "taskId")?.let { taskId ->
            return allowOrForbid(response, ownershipChecks.isOwnTask(trainee.login, taskId))
        }

        // ProcessResource: POST /api/v1/process/{processInstanceId}/delete — confirmed from Valtimo
        // source before relying on the name, not guessed (this repo's admin-surface-guard filter
        // originally hard-blocked this outright; scoping it instead needed the exact path variable
        // name verified first, since a wrong guess would silently never match and fail open).
        pathVariable(request, "processInstanceId")?.let { processInstanceId ->
            return allowOrForbid(response, ownershipChecks.isOwnProcessInstance(trainee.login, processInstanceId))
        }

        // EpistolaAdminResource: GET .../configurations/{configurationId}/catalogs (list, read-only)
        // / POST .../configurations/{configurationId}/catalogs/{slug}/redeploy (mutation) — both
        // scope by the caller's own plugin configuration, same as isOwnPluginConfiguration
        // everywhere else. allowShared only for the GET: listing what's redeployable is read-only
        // reference, but redeploy overwrites the shared "demo" tenant's own catalog content, which
        // form-flow-demo's process-links and every other trainee's read-only view of it depend on
        // — a mutation against shared infrastructure, not the trainee's own data.
        pathVariable(request, "configurationId")?.let { configurationId ->
            val readOnly = request.method.equals("GET", ignoreCase = true)
            return allowOrForbid(
                response,
                ownershipChecks.isOwnPluginConfiguration(traineeIdentity, configurationId, allowShared = readOnly),
            )
        }

        // EpistolaAdminResource: POST .../pending/{executionId}/reconcile — manually retries the
        // caller's own stuck Epistola catch event. No allowShared: reconciling is a mutation, and
        // the shared template dossier should stay untouched by every trainee, not reconciled by
        // whichever one happens to click it.
        pathVariable(request, "executionId")?.let { executionId ->
            return allowOrForbid(response, ownershipChecks.isOwnExecution(trainee.login, executionId))
        }

        // Every case-definition management endpoint that changes something names its case type
        // in the path, and the branch above has already decided those. A mutation under that
        // prefix with no case key at all is one this package has not reviewed — refused rather
        // than let through, now that TrainingHttpSecurityConfigurer widens the whole prefix.
        // POST .../case-definition/draft names its key in the body instead and is checked by
        // TraineeOwnershipRequestBodyAdvice.
        if (isUnscopedManagementMutation(request)) {
            return allowOrForbid(response, false)
        }

        return true
    }

    private fun isUnscopedManagementMutation(request: HttpServletRequest): Boolean {
        if (request.method.equals("GET", ignoreCase = true)) return false
        val path: String = request.requestURI ?: return false
        if (path == "/api/management/v1/case-definition/draft") return false
        return MANAGEMENT_CASE_PREFIXES.any { path.startsWith(it) }
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

    private companion object {
        private val MANAGEMENT_CASE_PREFIXES =
            listOf(
                "/api/management/v1/case-definition/",
                "/api/management/v1/case/",
                "/api/management/v2/case/",
            )
    }
}