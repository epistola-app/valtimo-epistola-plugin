package app.epistola.valtimo.web.rest.dto;

/**
 * Connection health check result for a single Epistola plugin configuration.
 * Includes the server version if the connection was successful.
 */
public record ConnectionStatus(
        String configurationId,
        String configurationTitle,
        String tenantId,
        boolean reachable,
        long latencyMs,
        String errorMessage,
        String serverVersion
) {}
