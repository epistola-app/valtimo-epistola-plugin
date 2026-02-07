package app.epistola.valtimo.config;

import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.EpistolaServiceImpl;
import app.epistola.valtimo.web.rest.EpistolaCallbackResource;
import app.epistola.valtimo.web.rest.EpistolaPluginResource;
import com.ritense.plugin.service.PluginService;
import com.ritense.valueresolver.ValueResolverService;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RuntimeService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(EpistolaProperties.class)
public class EpistolaPluginAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EpistolaApiClientFactory.class)
    public EpistolaApiClientFactory epistolaApiClientFactory() {
        return new EpistolaApiClientFactory();
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaService.class)
    public EpistolaService epistolaService(EpistolaApiClientFactory apiClientFactory) {
        return new EpistolaServiceImpl(apiClientFactory);
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

    @Bean
    @ConditionalOnMissingBean(EpistolaCallbackResource.class)
    public EpistolaCallbackResource epistolaCallbackResource(RuntimeService runtimeService) {
        return new EpistolaCallbackResource(runtimeService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaHttpSecurityConfigurer.class)
    public EpistolaHttpSecurityConfigurer epistolaHttpSecurityConfigurer() {
        return new EpistolaHttpSecurityConfigurer();
    }
}
