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
package app.epistola.valtimo.valueresolver;

import com.ritense.valtimo.contract.BlueprintId;
import com.ritense.valtimo.contract.case_.CaseDefinitionId;
import com.ritense.valtimo.operaton.domain.OperatonTask;
import com.ritense.valueresolver.ValueResolverFactory;
import com.ritense.valueresolver.ValueResolverOption;
import com.ritense.valueresolver.ValueResolverPropertyKey;
import kotlin.Unit;
import org.operaton.bpm.engine.delegate.VariableScope;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Value resolver that exposes the <b>current user task's</b> identity to a Valtimo form at
 * server-side prefill time, under the {@code epistola:} prefix.
 *
 * <p>Why this exists: the Epistola Formio components (document preview, download, retry-form) need the
 * id of the user task whose form they're rendered in, so the backend can authorize their requests
 * ({@code OperatonTask:VIEW} on the exact task). Valtimo 13.x does not expose the active task id to a
 * Formio component at runtime in every task-open flow — the task-list / case-detail flow bulk-fetches
 * process links and never fires the per-task {@code GET /api/v2/process-link/task/{id}} call that the
 * frontend interceptor used to sniff. Form <i>prefill</i>, however, runs server-side in <b>all</b>
 * flows ({@code PrefillFormService.getPrefilledFormDefinition(formDefId, processInstanceId,
 * taskInstanceId)}), with the task in hand.
 *
 * <p>During task-form prefill Valtimo passes the {@link OperatonTask} itself as the
 * {@link VariableScope} to {@link #createResolver(String, VariableScope)} (see
 * {@code PrefillFormService.prefillValueResolverFields}). So a form field with
 * {@code properties.sourceKey = "epistola:taskId"} is prefilled with the task id and the component
 * reads it back from the form data — robustly, in every Valtimo task-open flow.
 *
 * <p>Supported keys: {@code taskId}, {@code executionId}, {@code taskDefinitionKey}. Outside a task
 * context (no {@code OperatonTask} scope) the resolver returns {@code null}, so non-task forms simply
 * leave the field empty.
 */
public class EpistolaTaskValueResolverFactory implements ValueResolverFactory {

    public static final String PREFIX = "epistola";

    static final String KEY_TASK_ID = "taskId";
    static final String KEY_EXECUTION_ID = "executionId";
    static final String KEY_TASK_DEFINITION_KEY = "taskDefinitionKey";

    /** The carrier field's {@code sourceKey} ({@value}); the single source of truth for the prefix:key. */
    public static final String SOURCE_KEY = PREFIX + ":" + KEY_TASK_ID;

    @Override
    public String supportedPrefix() {
        return PREFIX;
    }

    @Override
    public Function<String, Object> createResolver(String processInstanceId, VariableScope variableScope) {
        // The prefix ('epistola:') is already stripped; we receive e.g. "taskId".
        return requestedValue -> {
            if (!(variableScope instanceof OperatonTask task)) {
                // Not a task context (e.g. a start form). Nothing to resolve.
                return null;
            }
            return switch (requestedValue) {
                case KEY_TASK_ID -> task.getId();
                case KEY_TASK_DEFINITION_KEY -> task.getTaskDefinitionKey();
                case KEY_EXECUTION_ID -> resolveExecutionId(task);
                default -> null;
            };
        };
    }

    private static Object resolveExecutionId(OperatonTask task) {
        try {
            return task.getExecution() != null ? task.getExecution().getId() : null;
        } catch (Exception e) {
            // getExecution() is a convenience that may not be populated in every context; the task id
            // (KEY_TASK_ID) is the value we actually rely on, so fail soft here.
            return null;
        }
    }

    /**
     * Resolution without a process/task context (document-only). The task identity is only meaningful
     * inside a task, so there is nothing to resolve here — return a null resolver rather than throwing,
     * to stay inert if the configurator probes this prefix.
     */
    @Override
    public Function<String, Object> createResolver(String documentId) {
        return requestedValue -> null;
    }

    /**
     * Property-map entry point used by {@code ValueResolverService.resolveValues(processInstanceId,
     * variableScope, keys)} — the path Valtimo's form prefill actually takes. The Kotlin interface's
     * default would route this back to {@link #createResolver(String, VariableScope)}, but a Java
     * implementor must do it explicitly (Kotlin interface defaults aren't inherited in Java). Without
     * this, the prefill receives a null resolver and the carrier field never gets the task id.
     */
    @Override
    public Function<String, Object> createResolver(Map<String, ?> properties) {
        Object processInstanceId = properties.get(ValueResolverPropertyKey.PROCESS_INSTANCE_ID);
        Object variableScope = properties.get(ValueResolverPropertyKey.VARIABLE_SCOPE);
        if (processInstanceId instanceof String pid && variableScope instanceof VariableScope scope) {
            return createResolver(pid, scope);
        }
        return requestedValue -> null;
    }

    @Override
    public Function<String, Unit> createValidator(String documentDefinitionName) {
        // Any 'epistola:<key>' reference is considered valid; resolution is null-safe at runtime.
        return requestedValue -> Unit.INSTANCE;
    }

    @Override
    public void handleValues(String processInstanceId, VariableScope variableScope, Map<String, ?> values) {
        // Read-only resolver: task identity is never written back.
    }

    @Override
    public void handleValues(UUID documentId, Map<String, ?> values) {
        // Read-only resolver.
    }

    @Override
    public Object preProcessValuesForNewCase(Map<String, ?> values) {
        return values;
    }

    @Override
    public List<ValueResolverOption> getResolvableKeyOptions(CaseDefinitionId caseDefinitionId) {
        return Collections.emptyList();
    }

    @Override
    public List<ValueResolverOption> getResolvableKeyOptions(String caseDefinitionKey) {
        return Collections.emptyList();
    }

    @Override
    public List<ValueResolverOption> getResolvableKeyOptions(BlueprintId blueprintId) {
        return Collections.emptyList();
    }

    @Override
    public List<ValueResolverOption> createFieldList(List<String> paths) {
        return Collections.emptyList();
    }
}
