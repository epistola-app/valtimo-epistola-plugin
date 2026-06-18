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
package app.epistola.valtimo.config;

import app.epistola.valtimo.service.download.ProcessVariableStorageStrategy;
import app.epistola.valtimo.service.download.TemporaryResourceStorageStrategy;
import com.ritense.resource.service.TemporaryResourceStorageService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@code download-document} storage strategies. Kept separate from
 * {@link EpistolaPluginAutoConfiguration} (which {@code @Import}s it) so the conditional wiring
 * (which target is available) can be unit-tested in isolation with an {@code ApplicationContextRunner}.
 *
 * <p>{@code @AutoConfiguration} so that {@code @ConditionalOnBean} evaluates after user-supplied
 * beans (its imports list registration is intentionally omitted — it is loaded only via
 * {@code @Import}). See {@code docs/adr/0001-download-document-content-storage.md}: no target is a
 * hard dependency — the temporary-resource strategy registers only when its backend is present, and
 * selecting an unavailable target fails at execution time with a clear error.
 */
@AutoConfiguration
public class EpistolaDownloadStorageConfiguration {

    /**
     * Inline {@code byte[]} storage. Always available (no backend); a supported non-default option
     * for small/non-sensitive documents.
     */
    @Bean
    @ConditionalOnMissingBean(ProcessVariableStorageStrategy.class)
    public ProcessVariableStorageStrategy processVariableStorageStrategy() {
        return new ProcessVariableStorageStrategy();
    }

    /**
     * Temporary-resource storage (the default target). Only registered when
     * {@code TemporaryResourceStorageService} is on the context, so the plugin imposes no hard
     * dependency on it.
     */
    @Bean
    @ConditionalOnBean(TemporaryResourceStorageService.class)
    @ConditionalOnMissingBean(TemporaryResourceStorageStrategy.class)
    public TemporaryResourceStorageStrategy temporaryResourceStorageStrategy(
            TemporaryResourceStorageService temporaryResourceStorageService
    ) {
        return new TemporaryResourceStorageStrategy(temporaryResourceStorageService);
    }
}
