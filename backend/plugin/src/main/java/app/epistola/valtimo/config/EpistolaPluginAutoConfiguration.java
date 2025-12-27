package app.epistola.valtimo.config;

import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.EpistolaServiceImpl;
import app.epistola.valtimo.web.rest.EpistolaPluginResource;
import com.ritense.plugin.service.PluginService;
import com.ritense.valueresolver.ValueResolverService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(EpistolaProperties.class)
public class EpistolaPluginAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EpistolaService.class)
    public EpistolaService epistolaService() {
        return new EpistolaServiceImpl();
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaPluginFactory.class)
    public EpistolaPluginFactory epistolaPluginFactory(
            PluginService pluginService,
            EpistolaService epistolaService,
            ValueResolverService valueResolverService
    ) {
        return new EpistolaPluginFactory(pluginService, epistolaService, valueResolverService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaPluginResource.class)
    public EpistolaPluginResource epistolaPluginResource(
            PluginService pluginService,
            EpistolaService epistolaService
    ) {
        return new EpistolaPluginResource(pluginService, epistolaService);
    }
}
