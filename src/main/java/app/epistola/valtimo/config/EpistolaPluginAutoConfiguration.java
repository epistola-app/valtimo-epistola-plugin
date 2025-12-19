package app.epistola.valtimo.config;

import app.epistola.valtimo.plugin.EpistolaPluginFactory;
import com.ritense.plugin.service.PluginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

@Slf4j
@EnableConfigurationProperties(EpistolaProperties.class)
public class EpistolaPluginAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EpistolaPluginFactory epistolaPluginFactory(
            PluginService pluginService,
            ApplicationEventPublisher publisher
    ) {
        return new EpistolaPluginFactory(pluginService, publisher);
    }

}
