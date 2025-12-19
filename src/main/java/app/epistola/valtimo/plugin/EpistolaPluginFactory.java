package app.epistola.valtimo.plugin;

import com.ritense.plugin.PluginFactory;
import com.ritense.plugin.service.PluginService;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;

public class EpistolaPluginFactory extends PluginFactory<EpistolaPlugin> {

    private final ApplicationEventPublisher publisher;

    public EpistolaPluginFactory(
            @NotNull PluginService pluginService,
            @NotNull ApplicationEventPublisher publisher
    ) {
        super(pluginService);
        this.publisher = publisher;
    }

    @NotNull
    @Override
    protected EpistolaPlugin create() {
        return new EpistolaPlugin();
    }

}


