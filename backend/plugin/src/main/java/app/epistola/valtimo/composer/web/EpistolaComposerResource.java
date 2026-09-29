/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package app.epistola.valtimo.composer.web;

import app.epistola.valtimo.composer.ComposerException;
import app.epistola.valtimo.composer.LetterComposerService;
import app.epistola.valtimo.composer.LetterComposerService.ComposerContext;
import app.epistola.valtimo.composer.LetterComposerService.PreparedLetter;
import app.epistola.valtimo.web.rest.StartEventAuthorization;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.authorization.AuthorizationService;
import com.ritense.authorization.request.EntityAuthorizationRequest;
import com.ritense.valtimo.operaton.domain.OperatonTask;
import com.ritense.valtimo.service.OperatonTaskService;
import com.ritense.valtimo.operaton.authorization.OperatonTaskActionProvider;
import com.ritense.valtimo.security.exceptions.TaskNotFoundException;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Endpoints behind the {@code epistola-letter-composer} component: prepare a chosen letter, and
 * preview it with the data the employee has in front of them.
 *
 * <p>Both authorize on {@code OperatonTask:VIEW} and then derive everything else from that task —
 * the process instance, the case document, and the activity whose form carries the composer's
 * configuration. The caller names a template, never a mapping or a catalog, and the backend
 * refuses a template the form does not offer (ADR 0006). So the worst a crafted request can do is
 * ask for a letter the caller can already open the form for.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola")
@SkipComponentScan
@RequiredArgsConstructor
public class EpistolaComposerResource {

    private final LetterComposerService letterComposerService;
    private final AuthorizationService authorizationService;
    private final OperatonTaskService operatonTaskService;
    /** The start-form gate, shared with the document preview so the two cannot drift apart. */
    private final StartEventAuthorization startEventAuthorization;

    /** What the composer needs to render one letter's inputs: the task and the chosen template. */
    public record PrepareRequest(String taskId, String catalogId, String templateId, String componentKey) {
    }

    /** A prepared letter: the mapped data, plus a form asking for whatever the mapping left empty. */
    public record PrepareResponse(
            String templateId,
            String label,
            String catalogId,
            Map<String, Object> data,
            ObjectNode form,
            boolean complete,
            /**
             * On a start form, which process this composer turned out to belong to — null on a
             * task form, where there is none. Sent back so the preview, which fires on every edit,
             * names the process instead of making the backend search for it again.
             */
            String processDefinitionKey
    ) {
        static PrepareResponse of(PreparedLetter letter) {
            return of(letter, null);
        }

        static PrepareResponse of(PreparedLetter letter, String processDefinitionKey) {
            return new PrepareResponse(
                    letter.templateId(),
                    letter.label(),
                    letter.catalogId(),
                    letter.data(),
                    letter.form(),
                    letter.complete(),
                    processDefinitionKey);
        }
    }

    /** Preview a letter with the data assembled so far (mapping result plus the employee's input). */
    public record ComposerPreviewRequest(
            String taskId,
            String catalogId,
            String templateId,
            String componentKey,
            Map<String, Object> data
    ) {
    }

    /**
     * The same two calls, from a <b>start form</b>: an ad-hoc letter on an open dossier, where no
     * task exists yet. The caller names the process by its version-stable key — a form stores that
     * rather than a version-pinned id, so a redeployment does not break it.
     */
    public record StartPrepareRequest(
            String processDefinitionKey,
            String documentId,
            String catalogId,
            String templateId,
            String componentKey
    ) {
    }

    /** Preview an ad-hoc letter with the data assembled so far. */
    public record StartPreviewRequest(
            String processDefinitionKey,
            String documentId,
            String catalogId,
            String templateId,
            String componentKey,
            Map<String, Object> data
    ) {
    }

    @PostMapping("/composer/prepare")
    public ResponseEntity<?> prepare(@RequestBody PrepareRequest request) {
        if (isBlank(request.taskId()) || isBlank(request.templateId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "taskId and templateId are required"));
        }

        OperatonTask task;
        try {
            task = requireTaskViewable(request.taskId());
        } catch (TaskNotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        try {
            return ResponseEntity.ok(PrepareResponse.of(
                    letterComposerService.prepare(contextOf(task, request.componentKey()),
                            trimToNull(request.catalogId()), request.templateId())));
        } catch (ComposerException e) {
            return mapComposerError(e);
        }
    }

    @PostMapping("/composer/preview")
    public ResponseEntity<?> preview(@RequestBody ComposerPreviewRequest request) {
        if (isBlank(request.taskId()) || isBlank(request.templateId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "taskId and templateId are required"));
        }

        OperatonTask task;
        try {
            task = requireTaskViewable(request.taskId());
        } catch (TaskNotFoundException e) {
            return ResponseEntity.notFound().build();
        }

        try {
            return pdfResponse(
                    letterComposerService.preview(
                            contextOf(task, request.componentKey()),
                            trimToNull(request.catalogId()),
                            request.templateId(),
                            request.data()));
        } catch (ComposerException e) {
            return mapComposerError(e);
        }
    }

    private ResponseEntity<?> pdfResponse(java.io.InputStream pdf) {
        var resource = new org.springframework.core.io.InputStreamResource(pdf);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.inline().filename("preview.pdf").build());
        return ResponseEntity.ok().headers(headers).body(resource);
    }

    @PostMapping("/composer/prepare/start")
    public ResponseEntity<?> prepareOnStartForm(@RequestBody StartPrepareRequest request) {
        if (isBlank(request.templateId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "templateId is required"));
        }

        StartEventAuthorization.StartContext startContext;
        try {
            startContext = startContextFor(
                    request.processDefinitionKey(),
                    request.documentId(),
                    request.componentKey(),
                    trimToNull(request.catalogId()),
                    request.templateId());
        } catch (StartEventAuthorization.NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ComposerException e) {
            return mapComposerError(e);
        }

        try {
            return ResponseEntity.ok(PrepareResponse.of(
                    letterComposerService.prepare(
                            ComposerContext.forStartEvent(
                                    startContext.processDefinitionId(),
                                    startContext.documentId(),
                                    request.componentKey()),
                            trimToNull(request.catalogId()),
                            request.templateId()),
                    startContext.processDefinitionKey()));
        } catch (ComposerException e) {
            return mapComposerError(e);
        }
    }

    @PostMapping("/composer/preview/start")
    public ResponseEntity<?> previewOnStartForm(@RequestBody StartPreviewRequest request) {
        if (isBlank(request.templateId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "templateId is required"));
        }

        StartEventAuthorization.StartContext startContext;
        try {
            startContext = startContextFor(
                    request.processDefinitionKey(),
                    request.documentId(),
                    request.componentKey(),
                    trimToNull(request.catalogId()),
                    request.templateId());
        } catch (StartEventAuthorization.NotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (ComposerException e) {
            return mapComposerError(e);
        }

        try {
            return pdfResponse(letterComposerService.preview(
                    ComposerContext.forStartEvent(
                            startContext.processDefinitionId(),
                            startContext.documentId(),
                            request.componentKey()),
                    trimToNull(request.catalogId()),
                    request.templateId(),
                    request.data()));
        } catch (ComposerException e) {
            return mapComposerError(e);
        }
    }

    /**
     * The authorized start context, whether or not the author named a process.
     *
     * <p>A start form belongs to exactly one process, so naming it is configuration the system can
     * work out for itself — but only server-side: Valtimo hands a Form.io component nothing about
     * the link that rendered it. When no key is given the processes whose start form carries this
     * composer are looked up, narrowed to the ones this caller may actually start on this case,
     * and used when exactly one survives. An authored key still wins, and is the way out when two
     * processes offer the same letter from a composer keyed the same way.
     *
     * <p>Discovery changes nothing about the gates: the surviving definition goes through the same
     * two checks as a named one, and a caller who may start nothing sees the same "no composer"
     * answer as one whose form carries none — never a list of processes they have no part in.
     *
     * <p>It is also not free — it reads every deployed definition's process links — so the answer
     * is handed back on {@code prepare} and named on the calls that follow. Preview fires on every
     * edit; searching again each time would make a letter's cost grow with the number of processes
     * deployed on the installation.
     */
    private StartEventAuthorization.StartContext startContextFor(
            String processDefinitionKey,
            String documentId,
            String componentKey,
            String catalogId,
            String templateId
    ) {
        String trimmedDocumentId = trimToNull(documentId);
        if (!isBlank(processDefinitionKey)) {
            return startEventAuthorization.require(processDefinitionKey, trimmedDocumentId);
        }

        var document = startEventAuthorization.findDocument(trimmedDocumentId);
        if (trimmedDocumentId != null && document == null) {
            throw new StartEventAuthorization.NotFoundException("Case document not found: " + trimmedDocumentId);
        }

        List<String> startable = letterComposerService
                .startEventDefinitionsOffering(componentKey, catalogId, templateId).stream()
                .filter(definitionId -> startEventAuthorization.permits(definitionId, document))
                .toList();

        if (startable.isEmpty()) {
            throw new ComposerException(ComposerException.Reason.NO_COMPOSER,
                    "No process you can start has a start form offering template '" + templateId + "'");
        }
        if (startable.size() > 1) {
            throw new ComposerException(ComposerException.Reason.MISSING_CONTEXT,
                    "Template '" + templateId + "' is offered by the start form of " + startable.size()
                            + " processes. Name the process on the composer to say which one.");
        }
        return startEventAuthorization.requireById(startable.get(0), trimmedDocumentId);
    }

    /**
     * Everything the composer works from is taken off the authorized task, so none of it can be
     * forged: the form link (and therefore the configuration), the case document and the variables
     * the mapping reads.
     */
    private ComposerContext contextOf(OperatonTask task, String componentKey) {
        return new ComposerContext(
                task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(),
                task.getProcessInstanceId(),
                task.getProcessInstance() != null ? task.getProcessInstance().getBusinessKey() : null,
                componentKey);
    }

    private OperatonTask requireTaskViewable(String taskId) {
        OperatonTask task = operatonTaskService.findTaskById(taskId);
        authorizationService.requirePermission(
                new EntityAuthorizationRequest<>(
                        OperatonTask.class,
                        OperatonTaskActionProvider.VIEW,
                        List.of(task)));
        return task;
    }

    /**
     * A misconfigured or unoffered template is the caller's request being wrong about this form
     * (400/404), while a refused render is Epistola rejecting the data (422) — the same split the
     * preview endpoint uses, so the component can show a usable message either way.
     */
    private ResponseEntity<?> mapComposerError(ComposerException e) {
        log.debug("Letter composer request failed ({}): {}", e.getReason(), e.getMessage());
        return switch (e.getReason()) {
            case NO_COMPOSER -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
            case TEMPLATE_NOT_OFFERED, MISSING_CONTEXT -> ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
            // Not the caller's request being wrong but this environment's: the same shape the
            // catalog sync uses for a wire schema it cannot read — operator-actionable, not 502.
            case RENDER_FAILED, UNSUPPORTED_SCHEMA, UNSUPPORTED_FIELD -> ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", e.getMessage()));
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
