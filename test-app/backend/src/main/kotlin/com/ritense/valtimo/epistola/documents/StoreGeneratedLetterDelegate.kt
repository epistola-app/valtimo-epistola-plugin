// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.documents

import com.ritense.resource.service.ResourceService
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.resource.service.request.RawFileUploadRequest
import com.ritense.valtimo.contract.annotation.ProcessBean
import com.ritense.valtimo.contract.document.event.DocumentRelatedFileSubmittedEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Keeps a generated letter on the case, so a case worker can open it after the process has ended.
 *
 * The plugin's `download-document` action puts the PDF in Valtimo's **temporary** resource storage,
 * which is cleaned up on a schedule (this demo keeps it for an hour) — right for handing a file to
 * the next step, wrong for the letter that was sent. This copies it into the demo's own table and
 * relates it to the case document, which is what makes it appear in the case's file list.
 *
 * Demo-only on purpose: the plugin owns no schema and should keep needing no database changes to
 * install, and a real installation points Valtimo at the Documenten API or S3 instead.
 *
 * Usage in BPMN:
 * ```
 * camunda:delegateExpression="${storeGeneratedLetter}"
 * ```
 *
 * [ProcessBean] is required, not decoration: Valtimo's `OperatonWhitelistedBeansPlugin` builds the
 * engine's bean map from `getBeansWithAnnotation(ProcessBean::class)`, so a plain `@Component` is
 * invisible to an expression. Without it the process reaches this task and fails with
 * `Cannot resolve identifier 'storeGeneratedLetter'` — measured, after the letter had already been
 * downloaded.
 * with input parameters `resourceIdVariable` (where the download action put the temporary resource
 * id) and, optionally, `fileName`.
 */
@ProcessBean
@Component("storeGeneratedLetter")
class StoreGeneratedLetterDelegate(
    private val temporaryResourceStorageService: TemporaryResourceStorageService,
    private val resourceService: ResourceService,
    private val applicationEventPublisher: ApplicationEventPublisher,
) : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        val resourceIdVariable =
            execution.getVariable("resourceIdVariable") as? String
                ?: throw IllegalArgumentException("Input parameter 'resourceIdVariable' is required")
        val temporaryResourceId =
            execution.getVariable(resourceIdVariable) as? String
                ?: throw IllegalArgumentException(
                    "No temporary resource id on '$resourceIdVariable'; does the download action run before this?",
                )

        // The case this process belongs to. Valtimo's dossier-driven processes carry the case
        // document UUID as the business key, the same derivation the plugin uses.
        val caseDocumentId =
            runCatching { UUID.fromString(execution.processBusinessKey) }.getOrNull()
                ?: throw IllegalStateException("This process has no case document to relate a letter to")

        val content = temporaryResourceStorageService.getResourceContentAsInputStream(temporaryResourceId).use { it.readBytes() }
        val fileName = execution.getVariable("fileName") as? String ?: "brief-$temporaryResourceId.pdf"

        val stored =
            resourceService.store(
                caseDocumentId.toString(),
                RawFileUploadRequest(
                    fileName.substringBeforeLast('.'),
                    fileName.substringAfterLast('.', "pdf"),
                    content.size.toLong(),
                    "application/pdf",
                    content,
                ),
            )

        // What makes it show up on the case: Valtimo's own listener turns this into a related file
        // on the case document. The document definition name is read back off the process, so this
        // delegate works for any demo case that uses it.
        applicationEventPublisher.publishEvent(
            DocumentRelatedFileSubmittedEvent(
                caseDocumentId,
                stored.id(),
                execution.getVariable("documentDefinitionName") as? String ?: documentDefinitionNameOf(execution),
            ),
        )

        logger.debug {
            "Stored a generated letter for case $caseDocumentId as resource ${stored.id()} (${content.size} bytes)"
        }
    }

    /**
     * The case type, taken from the process definition key by convention in this demo
     * (`correspondentie-...` belongs to `correspondentie`). Passing `documentDefinitionName` as an
     * input parameter overrides it where that does not hold.
     */
    private fun documentDefinitionNameOf(execution: DelegateExecution): String =
        execution.processEngineServices.repositoryService
            .getProcessDefinition(execution.processDefinitionId)
            .key
            .substringBefore('-')
}