package app.epistola.valtimo.config;

import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.deploy.EpistolaTemplateSyncService;
import app.epistola.valtimo.deploy.EpistolaTemplateSyncTrigger;
import app.epistola.valtimo.deploy.TemplateDefinitionScanner;
import app.epistola.valtimo.service.DataMappingResolverService;
import app.epistola.valtimo.service.EpistolaCompletionEventConsumer;
import app.epistola.valtimo.service.EpistolaMessageCorrelationService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.EpistolaServiceImpl;
import app.epistola.valtimo.service.FormioFormGenerator;
import app.epistola.valtimo.service.PollingCompletionEventConsumer;
import app.epistola.valtimo.service.ProcessVariableDiscoveryService;
import app.epistola.valtimo.service.RetryFormService;
import app.epistola.valtimo.web.rest.EpistolaCallbackResource;
import app.epistola.valtimo.web.rest.EpistolaPluginResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.plugin.service.PluginService;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valueresolver.ValueResolverService;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
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
    @ConditionalOnMissingBean(DataMappingResolverService.class)
    public DataMappingResolverService dataMappingResolverService(
            ValueResolverService valueResolverService
    ) {
        return new DataMappingResolverService(valueResolverService);
    }

    @Bean
    @ConditionalOnMissingBean(FormioFormGenerator.class)
    public FormioFormGenerator formioFormGenerator(ObjectMapper objectMapper) {
        return new FormioFormGenerator(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaPluginFactory.class)
    public EpistolaPluginFactory epistolaPluginFactory(
            PluginService pluginService,
            EpistolaService epistolaService,
            ValueResolverService valueResolverService,
            ObjectMapper objectMapper,
            DataMappingResolverService dataMappingResolverService
    ) {
        return new EpistolaPluginFactory(pluginService, epistolaService, valueResolverService,
                objectMapper, dataMappingResolverService);
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
    @ConditionalOnMissingBean(RetryFormService.class)
    public RetryFormService retryFormService(
            PluginService pluginService,
            EpistolaService epistolaService,
            RuntimeService runtimeService,
            TaskService taskService,
            ProcessLinkService processLinkService,
            DataMappingResolverService dataMappingResolverService,
            FormioFormGenerator formioFormGenerator,
            ObjectMapper objectMapper
    ) {
        return new RetryFormService(pluginService, epistolaService, runtimeService,
                taskService, processLinkService, dataMappingResolverService,
                formioFormGenerator, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(app.epistola.valtimo.service.PreviewService.class)
    public app.epistola.valtimo.service.PreviewService previewService(
            PluginService pluginService,
            EpistolaService epistolaService,
            ProcessLinkService processLinkService,
            com.ritense.valtimo.operaton.service.OperatonRepositoryService operatonRepositoryService,
            RuntimeService runtimeService,
            DataMappingResolverService dataMappingResolverService,
            ObjectMapper objectMapper
    ) {
        return new app.epistola.valtimo.service.PreviewService(pluginService, epistolaService,
                processLinkService, operatonRepositoryService, runtimeService,
                dataMappingResolverService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaPluginResource.class)
    public EpistolaPluginResource epistolaPluginResource(
            PluginService pluginService,
            EpistolaService epistolaService,
            ProcessVariableDiscoveryService processVariableDiscoveryService,
            RetryFormService retryFormService,
            app.epistola.valtimo.service.PreviewService previewService
    ) {
        return new EpistolaPluginResource(pluginService, epistolaService,
                processVariableDiscoveryService, retryFormService, previewService);
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
            EpistolaService epistolaService
    ) {
        return new PollingCompletionEventConsumer(
                runtimeService,
                pluginService,
                epistolaService
        );
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaHttpSecurityConfigurer.class)
    public EpistolaHttpSecurityConfigurer epistolaHttpSecurityConfigurer() {
        return new EpistolaHttpSecurityConfigurer();
    }

    @Bean
    @ConditionalOnMissingBean(TemplateDefinitionScanner.class)
    public TemplateDefinitionScanner templateDefinitionScanner(ObjectMapper objectMapper) {
        return new TemplateDefinitionScanner(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaTemplateSyncService.class)
    public EpistolaTemplateSyncService epistolaTemplateSyncService(
            TemplateDefinitionScanner scanner,
            EpistolaService epistolaService,
            ObjectMapper objectMapper
    ) {
        return new EpistolaTemplateSyncService(scanner, epistolaService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaTemplateSyncTrigger.class)
    public EpistolaTemplateSyncTrigger epistolaTemplateSyncTrigger(
            PluginService pluginService,
            EpistolaTemplateSyncService syncService
    ) {
        return new EpistolaTemplateSyncTrigger(pluginService, syncService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaFormAutoDeployAspect.class)
    @ConditionalOnProperty(name = "epistola.retry-form.enabled", havingValue = "true", matchIfMissing = true)
    public EpistolaFormAutoDeployAspect epistolaFormAutoDeployAspect(
            com.ritense.form.autodeployment.FormDefinitionDeploymentService formDeploymentService,
            EpistolaProperties properties
    ) {
        return new EpistolaFormAutoDeployAspect(formDeploymentService, properties);
    }
}
