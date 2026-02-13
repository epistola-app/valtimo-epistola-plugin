package app.epistola.valtimo.config;

import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.service.EpistolaCompletionEventConsumer;
import app.epistola.valtimo.service.EpistolaMessageCorrelationService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.EpistolaServiceImpl;
import app.epistola.valtimo.service.PollingCompletionEventConsumer;
import app.epistola.valtimo.service.ProcessVariableDiscoveryService;
import app.epistola.valtimo.web.rest.EpistolaCallbackResource;
import app.epistola.valtimo.web.rest.EpistolaPluginResource;
import com.ritense.plugin.service.PluginService;
import com.ritense.valueresolver.ValueResolverService;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(EpistolaProperties.class)
@EnableScheduling
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
    @ConditionalOnMissingBean(ProcessVariableDiscoveryService.class)
    public ProcessVariableDiscoveryService processVariableDiscoveryService(
            HistoryService historyService,
            RepositoryService repositoryService
    ) {
        return new ProcessVariableDiscoveryService(historyService, repositoryService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaPluginResource.class)
    public EpistolaPluginResource epistolaPluginResource(
            PluginService pluginService,
            EpistolaService epistolaService,
            ProcessVariableDiscoveryService processVariableDiscoveryService
    ) {
        return new EpistolaPluginResource(pluginService, epistolaService, processVariableDiscoveryService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaMessageCorrelationService.class)
    public EpistolaMessageCorrelationService epistolaMessageCorrelationService(
            RuntimeService runtimeService
    ) {
        return new EpistolaMessageCorrelationService(runtimeService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaCallbackResource.class)
    public EpistolaCallbackResource epistolaCallbackResource(
            EpistolaMessageCorrelationService correlationService
    ) {
        return new EpistolaCallbackResource(correlationService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaCompletionEventConsumer.class)
    @ConditionalOnProperty(name = "epistola.poller.enabled", havingValue = "true", matchIfMissing = true)
    public PollingCompletionEventConsumer pollingCompletionEventConsumer(
            RuntimeService runtimeService,
            PluginService pluginService,
            EpistolaService epistolaService,
            EpistolaMessageCorrelationService correlationService
    ) {
        return new PollingCompletionEventConsumer(
                runtimeService,
                pluginService,
                epistolaService,
                correlationService
        );
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaHttpSecurityConfigurer.class)
    public EpistolaHttpSecurityConfigurer epistolaHttpSecurityConfigurer() {
        return new EpistolaHttpSecurityConfigurer();
    }
}
