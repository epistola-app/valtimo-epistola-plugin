package app.epistola.valtimo.web.rest.dto;

import java.util.List;

/**
 * Describes a single usage of an Epistola plugin action within a process definition.
 */
public record PluginUsageEntry(
        String caseDefinitionKey,
        String caseDefinitionVersionTag,
        String processDefinitionKey,
        String processDefinitionName,
        String activityId,
        String activityName,
        String actionKey,
        String configurationId,
        String configurationTitle,
        List<String> problems
) {}
