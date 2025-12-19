package app.epistola.valtimo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration properties for Epistola Plugin.
 * Note that the spring boot configuration properties always take precedence over the configuration in the plugin.
 */
@Data
@ConfigurationProperties(prefix = "epistola.valtimo")
@Validated
public class EpistolaProperties {

    private Boolean enabled = false;
    private String runtimeBaseUrl;
    private String applicationName;
    private String applicationId;
    private String projectId;
    private Duration pingTimeout = Duration.ofSeconds(5);
    private Duration pingDelay = Duration.ofSeconds(10);
    private Boolean processAuditEvents = true;
    private Boolean processOutboxMessages = true;
    private Boolean processAsync = false;
}
