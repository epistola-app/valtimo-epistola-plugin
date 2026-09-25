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
package app.epistola.valtimo.composer;

import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.mapping.EvaluationContext;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.mapping.MapMerge;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.form.FormioFormGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.document.domain.impl.JsonSchemaDocumentId;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RuntimeService;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Prepares and previews a letter chosen from a {@code epistola-letter-composer} component.
 *
 * <p>The composer's promise is that a form offering many letters needs no field, preview or
 * override mapping per letter. That works because the two halves of a letter's data have different
 * sources: the baseline mapping fills whatever the case knows, and the employee is asked only for
 * what is left over. Which fields those are cannot be read from the mapping — it may be opaque or
 * computed — so they are read from its <i>outcome</i>, see {@link MissingFieldSelector}.
 *
 * <p>Everything configuration-shaped is read from the form definition behind the caller's task
 * (see {@link ComposerConfigurationResolver}); the caller only names one of the offered templates.
 */
@Slf4j
@RequiredArgsConstructor
public class LetterComposerService {

    private final ComposerConfigurationResolver configurationResolver;
    private final PluginService pluginService;
    private final EpistolaService epistolaService;
    private final JsonataMappingService jsonataMappingService;
    private final FormioFormGenerator formioFormGenerator;
    private final com.ritense.document.service.DocumentService documentService;
    private final RuntimeService runtimeService;
    private final ObjectMapper objectMapper;

    /**
     * Where the letter is being composed: all of it derived from the caller's task, never supplied
     * by the browser.
     *
     * @param processDefinitionId The definition holding the form link to read the configuration from
     * @param activityId          The user task's activity id
     * @param processInstanceId   Backs {@code $pv} in the mapping
     * @param documentId          The case document, backing {@code $doc} in the mapping
     * @param componentKey        Which composer on that form is asking, when the caller names it
     */
    public record ComposerContext(
            String processDefinitionId,
            String activityId,
            String processInstanceId,
            String documentId,
            String componentKey
    ) {
        /**
         * Composing from a start form: the process has not started, so there is no task to name an
         * activity from and no process instance for {@code $pv} to read. The configuration comes
         * from the definition's start form instead — see
         * {@link ComposerConfigurationResolver#forStartEvent}.
         */
        public static ComposerContext forStartEvent(
                String processDefinitionId,
                String documentId,
                String componentKey
        ) {
            return new ComposerContext(processDefinitionId, null, null, documentId, componentKey);
        }

        /** True when there is no task, i.e. the letter is composed on a start form. */
        public boolean isStartEvent() {
            return activityId == null;
        }
    }

    /**
     * A letter ready to be filled in.
     *
     * @param templateId   The chosen template
     * @param label        Its label as configured on the component
     * @param catalogId    The catalog it lives in
     * @param data         What the baseline mapping produced for this case
     * @param form         A Formio form asking for the fields the mapping left empty
     * @param complete     True when the mapping filled everything and nothing has to be asked
     */
    public record PreparedLetter(
            String templateId,
            String label,
            String catalogId,
            Map<String, Object> data,
            ObjectNode form,
            boolean complete
    ) {
    }

    /**
     * Resolve a letter's data for this case and build the input form for what is still missing.
     */
    public PreparedLetter prepare(ComposerContext ctx, String templateId) {
        LetterComposerConfiguration configuration = configurationFor(ctx, templateId);
        var offered = configuration.findTemplate(templateId);

        Map<String, Object> data = resolveData(ctx, configuration, offered);
        TemplateDetails template = templateDetails(configuration, templateId);

        List<TemplateField> missing = MissingFieldSelector.selectMissing(
                template.fields(), data, !configuration.askOptionalFields());
        ObjectNode form = formioFormGenerator.generateForm(missing, data);

        log.debug("Prepared letter '{}' for case {}: {} field(s) to ask",
                templateId, ctx.documentId(), missing.size());

        return new PreparedLetter(
                templateId,
                offered.label(),
                configuration.catalogId(),
                data,
                form,
                missing.isEmpty());
    }

    /**
     * Render a preview of the letter with the data the employee has in front of them.
     *
     * <p>The data arrives from the browser because the composer is what assembled it: the same
     * object is stored on the form and handed to generation afterwards, so the preview shows
     * exactly what will be generated (ADR 0006).
     */
    public InputStream preview(ComposerContext ctx, String templateId, Map<String, Object> data) {
        LetterComposerConfiguration configuration = configurationFor(ctx, templateId);
        EpistolaPlugin plugin = plugin(configuration);

        try {
            return epistolaService.previewDocument(
                    plugin.getBaseUrl(),
                    plugin.getApiKey(),
                    plugin.getTenantId(),
                    configuration.catalogId(),
                    templateId,
                    null,
                    plugin.getDefaultEnvironmentId(),
                    data == null ? Map.of() : data);
        } catch (RuntimeException e) {
            throw new ComposerException(ComposerException.Reason.RENDER_FAILED,
                    "Epistola could not render template '" + templateId + "': " + e.getMessage(), e);
        }
    }

    private LetterComposerConfiguration configurationFor(ComposerContext ctx, String templateId) {
        return ctx.isStartEvent()
                ? configurationResolver.requireStartOffering(
                        ctx.processDefinitionId(), ctx.componentKey(), templateId)
                : configurationResolver.requireOffering(
                        ctx.processDefinitionId(), ctx.activityId(), ctx.componentKey(), templateId);
    }

    /**
     * Evaluate the baseline mapping, then the template's own fragment on top of it. Two small
     * mappings beat one big conditional: the baseline says what every letter of this case type
     * needs, the fragment only what makes this letter different.
     */
    private Map<String, Object> resolveData(
            ComposerContext ctx,
            LetterComposerConfiguration configuration,
            LetterComposerConfiguration.OfferedTemplate offered
    ) {
        Map<String, Object> data = evaluate(ctx, configuration.dataMapping());
        if (offered.dataMapping() != null && !offered.dataMapping().isBlank()) {
            data = MapMerge.deepMerge(data, evaluate(ctx, offered.dataMapping()));
        }
        return data;
    }

    private Map<String, Object> evaluate(ComposerContext ctx, String mapping) {
        if (mapping == null || mapping.isBlank()) {
            return Map.of();
        }
        var evalCtx = EvaluationContext.builder()
                .expression(mapping)
                .documentResolver(this::loadDocumentContent)
                .processVariableResolver(name -> ctx.processInstanceId() == null
                        ? null
                        : runtimeService.getVariable(ctx.processInstanceId(), name))
                .processVariableEnumerator(() -> ctx.processInstanceId() == null
                        ? Map.of()
                        : runtimeService.getVariables(ctx.processInstanceId()))
                .documentId(ctx.documentId())
                .operation("compose")
                .processDefinitionId(ctx.processDefinitionId())
                .processInstanceId(ctx.processInstanceId())
                .activityId(ctx.activityId())
                .build();
        return jsonataMappingService.evaluate(evalCtx);
    }

    private TemplateDetails templateDetails(LetterComposerConfiguration configuration, String templateId) {
        EpistolaPlugin plugin = plugin(configuration);
        return epistolaService.getTemplateDetails(
                plugin.getBaseUrl(),
                plugin.getApiKey(),
                plugin.getTenantId(),
                configuration.catalogId(),
                templateId);
    }

    private EpistolaPlugin plugin(LetterComposerConfiguration configuration) {
        if (configuration.pluginConfigurationId() == null) {
            throw new ComposerException(ComposerException.Reason.MISSING_CONTEXT,
                    "Letter composer '" + configuration.componentKey() + "' has no plugin configuration");
        }
        return (EpistolaPlugin) pluginService.createInstance(configuration.pluginConfigurationId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDocumentContent(String documentId) {
        if (documentId == null) {
            return Map.of();
        }
        try {
            var document = documentService.findBy(JsonSchemaDocumentId.existingId(java.util.UUID.fromString(documentId)));
            if (document.isEmpty()) {
                return Map.of();
            }
            return objectMapper.convertValue(document.get().content().asJson(), Map.class);
        } catch (Exception e) {
            log.warn("Could not load case document {} while composing a letter: {}", documentId, e.getMessage());
            return Map.of();
        }
    }
}
