package app.epistola.valtimo.plugin;

import app.epistola.valtimo.service.EpistolaService;
import com.ritense.plugin.PluginFactory;
import com.ritense.plugin.service.PluginService;
import com.ritense.valueresolver.ValueResolverService;
import org.jetbrains.annotations.NotNull;

public class EpistolaPluginFactory extends PluginFactory<EpistolaPlugin> {

    private final EpistolaService epistolaService;
    private final ValueResolverService valueResolverService;

    public EpistolaPluginFactory(
            @NotNull PluginService pluginService,
            @NotNull EpistolaService epistolaService,
            @NotNull ValueResolverService valueResolverService
    ) {
        super(pluginService);
        this.epistolaService = epistolaService;
        this.valueResolverService = valueResolverService;
    }

    @NotNull
    @Override
    protected EpistolaPlugin create() {
        return new EpistolaPlugin(epistolaService, valueResolverService);
    }
}
