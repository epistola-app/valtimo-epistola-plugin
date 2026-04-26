package app.epistola.valtimo.web.rest.dto;

/**
 * Version information for the Epistola plugin and connected server.
 */
public record VersionInfo(
        String pluginVersion,
        String epistolaServerVersion
) {}
