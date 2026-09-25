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

import app.epistola.valtimo.composer.web.EpistolaComposerResource;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.form.FormioFormGenerator;
import app.epistola.valtimo.web.rest.StartEventAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.authorization.AuthorizationService;
import com.ritense.document.service.DocumentService;
import com.ritense.form.repository.FormDefinitionRepository;
import com.ritense.plugin.service.PluginService;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valtimo.service.OperatonTaskService;
import org.operaton.bpm.engine.RuntimeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Everything the letter composer needs, in one place.
 *
 * <p>The composer is a feature of its own: an employee picking a letter, seeing what the case
 * cannot supply, and previewing it. Nothing else in the plugin depends on it, so it is wired here
 * rather than in the general auto-configuration, and an environment that does not offer letters
 * this way can switch it off with {@code epistola.composer.enabled=false} — leaving no endpoints
 * and no beans.
 *
 * <p>What it depends on, it depends on narrowly: the Epistola API, the JSONata mapping service, the
 * Formio form generator and Valtimo's own services. The generation step itself is a plugin action
 * on {@code EpistolaPlugin}, because Valtimo scans the plugin class for actions; its behaviour
 * lives in {@link ComposedLetter}.
 */
@Configuration
@ConditionalOnProperty(prefix = "epistola.composer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EpistolaComposerConfiguration {

    @Bean
    @ConditionalOnMissingBean(ComposerConfigurationResolver.class)
    public ComposerConfigurationResolver composerConfigurationResolver(
            ProcessLinkService processLinkService,
            FormDefinitionRepository formDefinitionRepository
    ) {
        return new ComposerConfigurationResolver(processLinkService, formDefinitionRepository);
    }

    @Bean
    @ConditionalOnMissingBean(LetterComposerService.class)
    public LetterComposerService letterComposerService(
            ComposerConfigurationResolver composerConfigurationResolver,
            PluginService pluginService,
            EpistolaService epistolaService,
            JsonataMappingService jsonataMappingService,
            FormioFormGenerator formioFormGenerator,
            DocumentService documentService,
            RuntimeService runtimeService,
            ObjectMapper objectMapper
    ) {
        return new LetterComposerService(composerConfigurationResolver, pluginService, epistolaService,
                jsonataMappingService, formioFormGenerator, documentService, runtimeService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaComposerResource.class)
    public EpistolaComposerResource epistolaComposerResource(
            LetterComposerService letterComposerService,
            AuthorizationService authorizationService,
            OperatonTaskService operatonTaskService,
            StartEventAuthorization startEventAuthorization
    ) {
        return new EpistolaComposerResource(letterComposerService, authorizationService,
                operatonTaskService, startEventAuthorization);
    }
}
