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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Epistola Plugin runtime behavior.
 */
@Data
@ConfigurationProperties(prefix = "epistola")
@Validated
public class EpistolaProperties {

    /**
     * Whether the Epistola plugin is enabled.
     * When set to false, no Epistola beans are registered.
     */
    private boolean enabled = true;

    private final ResultCollector resultCollector = new ResultCollector();
    private final RetryForm retryForm = new RetryForm();
    private final CatchEventAutoWiring catchEventAutoWiring = new CatchEventAutoWiring();

    @Data
    public static class CatchEventAutoWiring {

        /**
         * Whether the plugin auto-wires {@code EpistolaDocumentGenerated} catch events via Operaton's
         * process-engine parse SPI (a {@code ProcessEnginePlugin} + {@code BpmnParseListener}). Default
         * true. Set to false to disable the engine-SPI integration entirely — an escape hatch should a
         * future Operaton version break the SPI. Correlation then still works for any catch event that
         * declares the {@code epistolaWaitFor} {@code camunda:inputParameter} mapping explicitly.
         */
        private boolean enabled = true;
    }

    @Data
    public static class RetryForm {

        /**
         * Whether to auto-deploy the epistola-retry-document form for each case.
         */
        private boolean enabled = true;

        /**
         * Filter which cases get the retry form deployed.
         * "all" (default) — deploy for every case.
         * "none" — deploy for no cases (same as enabled=false).
         * Any other value is treated as a regex matched against the case definition key.
         * Example: "permit.*|subsidy.*"
         */
        private String caseFilter = "all";
    }

    @Data
    public static class ResultCollector {

        /**
         * Whether the result collector is enabled.
         * When false, generated documents will not be picked up automatically.
         */
        private boolean enabled = true;

        /**
         * Maximum number of results requested per /generation/collect call.
         */
        private int batchSize = 100;

        /**
         * Minimum poll interval (ms) when results are flowing.
         */
        private long minIntervalMs = 1000;

        /**
         * Maximum poll interval (ms) when the queue is idle.
         */
        private long maxIntervalMs = 30000;

        /**
         * Reconciliation interval (ms) for the runner to check for new/removed
         * plugin configurations and start/stop their collectors accordingly.
         */
        private long reconcileIntervalMs = 60000;

        /**
         * Wait used by `kick()` to override the current backoff (ms).
         * After successfully submitting a generate (which the plugin actions
         * report via {@link app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner#kickFor}),
         * if the collector has backed off past this interval it resets to
         * `kickIntervalMs` so the next poll happens within ~3s instead of
         * waiting the full backoff. Should be long enough that the suite
         * has had a chance to actually emit the row before we poll for it.
         */
        private long kickIntervalMs = 3000;

        /**
         * Exponential backoff multiplier applied to the current poll interval
         * on each empty poll. Higher values reach `maxIntervalMs` faster,
         * reducing idle poll volume; the kick mechanism is the safety net
         * that gets us back to fast polling when a result is expected.
         * Default 3.0 gives the sequence 1s → 3s → 9s → 27s → 30s (capped).
         */
        private double backoffMultiplier = 3.0;
    }
}
