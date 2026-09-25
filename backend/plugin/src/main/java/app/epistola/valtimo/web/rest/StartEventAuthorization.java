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
package app.epistola.valtimo.web.rest;

import com.ritense.authorization.AuthorizationContext;
import com.ritense.authorization.AuthorizationService;
import com.ritense.authorization.request.AuthorizationResourceContext;
import com.ritense.authorization.request.EntityAuthorizationRequest;
import com.ritense.authorization.request.RelatedEntityAuthorizationRequest;
import com.ritense.document.domain.impl.JsonSchemaDocument;
import com.ritense.document.domain.impl.JsonSchemaDocumentId;
import com.ritense.document.service.DocumentService;
import com.ritense.document.service.JsonSchemaDocumentActionProvider;
import com.ritense.valtimo.operaton.authorization.OperatonExecutionActionProvider;
import com.ritense.valtimo.operaton.domain.OperatonExecution;
import com.ritense.valtimo.operaton.domain.OperatonProcessDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;

import java.util.List;
import java.util.UUID;

/**
 * Authorizes the endpoints a component may call from a <b>start form</b>, where there is no task to
 * derive anything from.
 *
 * <p>One implementation on purpose: the document preview and the letter composer both offer a
 * start-form mode, and a difference between their checks would be a security hole in whichever one
 * drifted. The reasoning behind the two gates is ADR 0004.
 */
@Slf4j
@RequiredArgsConstructor
public class StartEventAuthorization {

    private final RepositoryService repositoryService;
    private final DocumentService documentService;
    private final AuthorizationService authorizationService;

    /**
     * A resolved and authorized start context.
     *
     * @param processDefinitionId The deployed definition the caller named by key
     * @param documentId          The case the process would start on, or null for a new case
     * @param document            That case, loaded, or null
     */
    public record StartContext(String processDefinitionId, String documentId, JsonSchemaDocument document) {
    }

    /** Raised when the process definition or the case document does not exist — a 404, not a 403. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Resolve the definition and case, and authorize the caller to start that process against it.
     *
     * @param processDefinitionKey The version-stable key a form stores (never a version-pinned id,
     *                             which a redeployment would invalidate)
     * @param documentId           The case to start on, or null for a new case
     * @throws NotFoundException           if the key or the document is unknown
     * @throws org.springframework.security.access.AccessDeniedException if the caller may not start it
     */
    public StartContext require(String processDefinitionKey, String documentId) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processDefinitionKey)
                .latestVersion()
                .singleResult();
        if (definition == null) {
            throw new NotFoundException("No deployed process definition for key '" + processDefinitionKey + "'");
        }

        JsonSchemaDocument document = null;
        if (documentId != null) {
            document = findDocumentOrNull(documentId);
            if (document == null) {
                throw new NotFoundException("Case document not found: " + documentId);
            }
        }

        // PRIMARY GATE — may this caller start this process? Same check as
        // ProcessLinkActivityService.getStartEventObject, including the document context.
        // NB: deliberately NOT JsonSchemaDocumentDefinition:CREATE — despite the name, that action
        // means "may deploy a case schema" (it is used only by JsonSchemaDocumentDefinitionService
        // .deploy) and would make these endpoints admin-only. See ADR 0004.
        var executionRequest = new RelatedEntityAuthorizationRequest<>(
                OperatonExecution.class,
                OperatonExecutionActionProvider.CREATE,
                OperatonProcessDefinition.class,
                definition.getId());
        if (document != null) {
            executionRequest = executionRequest.withContext(
                    new AuthorizationResourceContext<>(JsonSchemaDocument.class, document));
        }
        authorizationService.requirePermission(executionRequest);

        // SECONDARY GATE — CREATE on a process must never confer READ on a case. Stricter than
        // Valtimo's own start-form path, which passes the document only as context: it derives the
        // id from the route the user already navigated to, whereas we take it from the wire and
        // render its content into a PDF.
        if (document != null) {
            authorizationService.requirePermission(new EntityAuthorizationRequest<>(
                    JsonSchemaDocument.class,
                    JsonSchemaDocumentActionProvider.VIEW,
                    List.of(document)));
        }

        return new StartContext(definition.getId(), documentId, document);
    }

    /**
     * Look up a case document without authorizing — the caller authorizes it explicitly afterwards.
     * Returns null when the id is unknown or not a UUID, so a bad id is a 404 rather than a 500.
     */
    private JsonSchemaDocument findDocumentOrNull(String documentId) {
        try {
            var id = JsonSchemaDocumentId.existingId(UUID.fromString(documentId));
            return AuthorizationContext.runWithoutAuthorization(
                    () -> (JsonSchemaDocument) documentService.findBy(id).orElse(null));
        } catch (IllegalArgumentException e) {
            return null;
        } catch (Exception e) {
            log.debug("Could not resolve document {} for a start-form request: {}", documentId, e.getMessage());
            return null;
        }
    }
}
