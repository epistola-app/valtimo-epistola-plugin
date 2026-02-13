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

    private final Poller poller = new Poller();

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
