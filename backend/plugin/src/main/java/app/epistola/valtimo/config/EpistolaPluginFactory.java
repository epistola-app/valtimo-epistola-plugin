package app.epistola.valtimo.config;

import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.document.service.DocumentService;
import com.ritense.plugin.PluginFactory;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import com.ritense.valueresolver.ValueResolverService;
import org.jetbrains.annotations.NotNull;

public class EpistolaPluginFactory extends PluginFactory<EpistolaPlugin> {

    private final EpistolaService epistolaService;
    private final ValueResolverService valueResolverService;
    private final ObjectMapper objectMapper;
    private final JsonataMappingService jsonataMappingService;
    private final DocumentService documentService;

    public EpistolaPluginFactory(
            @NotNull PluginService pluginService,
            @NotNull EpistolaService epistolaService,
            @NotNull ValueResolverService valueResolverService,
            @NotNull ObjectMapper objectMapper,
            @NotNull JsonataMappingService jsonataMappingService,
            @NotNull DocumentService documentService
    ) {
        super(pluginService);
        this.epistolaService = epistolaService;
        this.valueResolverService = valueResolverService;
        this.objectMapper = objectMapper;
        this.jsonataMappingService = jsonataMappingService;
        this.documentService = documentService;
    }

    @NotNull
    @Override
    protected EpistolaPlugin create() {
        return new EpistolaPlugin(epistolaService, valueResolverService, objectMapper,
                jsonataMappingService, documentService);
    }
}
