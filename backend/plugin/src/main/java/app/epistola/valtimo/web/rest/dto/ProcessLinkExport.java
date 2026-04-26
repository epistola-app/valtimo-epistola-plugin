package app.epistola.valtimo.web.rest.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Export format for a single process link, matching Valtimo's .process-link.json auto-deploy format.
 */
public record ProcessLinkExport(
        String activityId,
        String activityType,
        String processLinkType,
        String pluginConfigurationId,
        String pluginActionDefinitionKey,
        ObjectNode actionProperties
) {}
