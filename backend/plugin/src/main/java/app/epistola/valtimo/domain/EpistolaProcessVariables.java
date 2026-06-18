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
package app.epistola.valtimo.domain;

/**
 * Constants for process variable names used by the Epistola plugin.
 * <p>
 * Centralises all magic strings so that plugin actions, REST endpoints,
 * and BPMN input parameters reference the same keys.
 */
public final class EpistolaProcessVariables {

    private EpistolaProcessVariables() {}

    /** Prefix for the composite job path: {@code epistola:job:{tenantId}/{requestId}}. */
    public static final String JOB_PATH_PREFIX = "epistola:job:";

    /** Tenant ID of the Epistola instance that handled the request. */
    public static final String TENANT_ID = "epistolaTenantId";

    /** JSON string with user-edited data from the retry form. Consumed and cleared by generate-document on retry. */
    public static final String EDITED_DATA = "epistolaEditedData";

    /** BPMN input parameter on the retry user task identifying the source generate-document activity. */
    public static final String SOURCE_ACTIVITY_ID = "epistolaSourceActivityId";

    /** BPMN message name correlated when document generation completes. */
    public static final String MESSAGE_NAME = "EpistolaDocumentGenerated";

    /**
     * Execution-local variable pinned on a waiting {@code EpistolaDocumentGenerated} catch event,
     * holding the composite jobPath of the generation it is waiting for. The result collector
     * correlates a completion by matching this value, so a result wakes exactly that branch's catch
     * event — independent of the execution-tree shape. Populated declaratively by a
     * {@code camunda:inputParameter} on the catch event ({@code ${<resultVar>.jobPath}}); a process
     * author may point it elsewhere to override.
     */
    public static final String WAIT_FOR = "epistolaWaitFor";

    /** Result-object key for the Epistola request id (UUID string). */
    public static final String RESULT_KEY_REQUEST_ID = "requestId";

    /**
     * Result-object key for the composite jobPath ({@code epistola:job:{tenantId}/{requestId}}).
     * Exposed inside the rich result so a catch event can pin its correlation token declaratively
     * with {@code ${<resultVar>.jobPath}}.
     */
    public static final String RESULT_KEY_JOB_PATH = "jobPath";

    /** Result-object key for the current job status (PENDING / IN_PROGRESS / COMPLETED / FAILED / CANCELLED). */
    public static final String RESULT_KEY_STATUS = "status";

    /** Result-object key for the generated document id (set on COMPLETED, null otherwise). */
    public static final String RESULT_KEY_DOCUMENT_ID = "documentId";

    /** Result-object key for the failure message (set on FAILED, null otherwise). */
    public static final String RESULT_KEY_ERROR_MESSAGE = "errorMessage";

    /** Whether a result-object {@code status} value is terminal (the generation has finished). */
    public static boolean isTerminalStatus(Object status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
