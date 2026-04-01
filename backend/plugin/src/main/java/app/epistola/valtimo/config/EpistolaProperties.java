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

    private final Poller poller = new Poller();
    private final RetryForm retryForm = new RetryForm();

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
    public static class Poller {

        /**
         * Whether the polling completion event consumer is enabled.
         * Disable when using webhooks or event API for completion notifications.
         */
        private boolean enabled = true;

        /**
         * Fixed delay in milliseconds between poll cycles.
         */
        private long interval = 30000;
    }
}
