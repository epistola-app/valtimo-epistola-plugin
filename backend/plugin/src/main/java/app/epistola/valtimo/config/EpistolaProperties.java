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

import java.time.Duration;

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
    private final CatchEventAutoWiring catchEventAutoWiring = new CatchEventAutoWiring();
    private final Client client = new Client();
    private final VersionCheck versionCheck = new VersionCheck();
    private final RichText richText = new RichText();
    private final CatalogSync catalogSync = new CatalogSync();

    /**
     * Where the plugin installs a bundled classpath catalog whose manifest predates catalog wire
     * v8. Since contract 2.0 a catalog is named by registry, namespace and key, and such a manifest
     * has only the key; a v8 manifest names its own, and these are not used for it.
     */
    @Data
    public static class CatalogSync {
        /**
         * Registry to name the catalog in. {@code local} needs no grant: it names catalogs that
         * never leave the organisation, which is what a catalog compiled into an application is.
         */
        private String registry = "local";

        /**
         * Namespace to name the catalog in, within {@link #registry}. Unset, the tenant's own: its key,
         * padded with {@code 0} to 3 characters, which is what Epistola's 2.0 upgrade named the
         * catalogs a tenant had, so the first sync after the upgrade adds to the catalog it migrated
         * instead of installing a second one beside it.
         */
        private String namespace;
    }

    @Data
    public static class RichText {

        /**
         * The format the plugin's own editors write rich text in, which picks the editor a person
         * types one in, and what {@code $richText(value)} reads without a format. One of the
         * shipped formats ({@code html}, {@code plain}, {@code markdown}, {@code prosemirror}) or
         * the id of an installation's own {@code RichTextFormat} bean. Defaults to {@code html}:
         * what Valtimo's WYSIWYG text areas write. A string mapped onto a rich-text field is never
         * read in it; the mapping names the format, {@code $richText($doc.x, "plain")}. What an
         * editor already submitted keeps the format it was typed in. See ADR 0008.
         */
        private String defaultFormat = "html";
    }

    @Data
    public static class VersionCheck {

        /**
         * Whether the plugin checks public release metadata for newer plugin versions.
         * Kept visible in the admin API even when disabled so operators can tell this
         * is an intentional configuration choice.
         */
        private boolean enabled = true;

        /**
         * Public release metadata document shared with Epistola Suite.
         */
        private String wellKnownUrl = "https://epistola.app/.well-known/epistola/releases.json";

        /**
         * Product key in the releases document.
         */
        private String productKey = "valtimo-epistola-plugin";

        /**
         * Stable Valtimo installation identifier to send with version-check requests.
         * When blank, the plugin derives a one-way fingerprint from the first
         * Liquibase database changelog execution timestamp.
         */
        private String valtimoInstallationId;

        /**
         * Daily UTC cron boundary for refreshing the cached status. The actual
         * request is delayed by a random per-node jitter to avoid synchronized
         * checks across clustered nodes.
         */
        private String cron = "0 0 5 * * *";

        /**
         * Time zone used by {@link #cron}.
         */
        private String zone = "UTC";

        /**
         * Maximum random delay after the scheduled cron boundary. The default
         * makes the daily check happen somewhere between 05:00 and 06:00 UTC.
         */
        private Duration maxJitter = Duration.ofHours(1);

        /**
         * How far ahead of support.until an admin warning starts showing.
         */
        private Duration deprecationWarningWindow = Duration.ofDays(90);

        /**
         * Connection timeout for release metadata fetches.
         */
        private Duration connectTimeout = Duration.ofSeconds(3);

        /**
         * Read timeout for release metadata fetches.
         */
        private Duration readTimeout = Duration.ofSeconds(5);
    }

    @Data
    public static class Client {

        /**
         * Connection-establish timeout (ms) applied to every outbound Epistola HTTP call,
         * including the result collector. A hung connect would otherwise block a BPMN
         * job-executor thread (or the collector's virtual thread) indefinitely.
         */
        private long connectTimeoutMs = 10000;

        /**
         * Socket read/inactivity timeout (ms) for short request/response API calls
         * (job status, submit, catalog/template/variant reads). Deliberately NOT applied
         * to streaming or long/large operations — the result collector poll, document
         * download, document preview, and catalog import — which only get the connect
         * timeout so a legitimately slow render or large transfer is not cut off.
         */
        private long readTimeoutMs = 30000;

        /**
         * Number of retry attempts (in addition to the first try) for idempotent reads
         * (job status, document download) on a transient failure — connect/read timeout,
         * connection refused, or a 5xx response. 4xx responses are never retried.
         */
        private int maxReadRetries = 2;
    }

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
