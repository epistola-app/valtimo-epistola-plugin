package app.epistola.valtimo.config;

import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.document.service.DocumentService;
import app.epistola.valtimo.domain.DocumentStorageTarget;
import app.epistola.valtimo.service.download.DocumentStorageStrategy;
import com.ritense.plugin.PluginFactory;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class EpistolaPluginFactory extends PluginFactory<EpistolaPlugin> {

    private final EpistolaService epistolaService;
    private final ObjectMapper objectMapper;
    private final JsonataMappingService jsonataMappingService;
    private final DocumentService documentService;
    private final EpistolaResultCollectorRunner resultCollectorRunner;
    private final Map<DocumentStorageTarget, DocumentStorageStrategy> storageStrategies;

    public EpistolaPluginFactory(
            @NotNull PluginService pluginService,
            @NotNull EpistolaService epistolaService,
            @NotNull ObjectMapper objectMapper,
            @NotNull JsonataMappingService jsonataMappingService,
            @NotNull DocumentService documentService,
            @NotNull EpistolaResultCollectorRunner resultCollectorRunner,
            @NotNull List<DocumentStorageStrategy> storageStrategies
    ) {
        super(pluginService);
        this.epistolaService = epistolaService;
        this.objectMapper = objectMapper;
        this.jsonataMappingService = jsonataMappingService;
        this.documentService = documentService;
        this.resultCollectorRunner = resultCollectorRunner;
        // Only the strategies whose backend is present are registered as beans (see auto-config),
        // so this map reflects what is actually available in this environment.
        this.storageStrategies = storageStrategies.stream().collect(Collectors.toMap(
                DocumentStorageStrategy::target,
                Function.identity(),
                (a, b) -> a,
                () -> new EnumMap<>(DocumentStorageTarget.class)));
    }

    @NotNull
    @Override
    protected EpistolaPlugin create() {
        return new EpistolaPlugin(epistolaService, objectMapper,
                jsonataMappingService, documentService, resultCollectorRunner,
                storageStrategies);
    }
}
