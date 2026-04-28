package app.epistola.valtimo.config;

import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.deploy.CatalogScanner;
import app.epistola.valtimo.deploy.EpistolaCatalogSyncService;
import app.epistola.valtimo.deploy.EpistolaCatalogSyncTrigger;
import app.epistola.valtimo.expression.EpistolaExpressionFunction;
import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import app.epistola.valtimo.expression.functions.FormatDateFunction;
import app.epistola.valtimo.expression.functions.StringFunctions;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.admin.EpistolaAdminService;
import app.epistola.valtimo.service.completion.EpistolaCompletionEventConsumer;
import app.epistola.valtimo.service.suggestion.VariableSuggestionService;
import app.epistola.valtimo.service.completion.EpistolaMessageCorrelationService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.EpistolaServiceImpl;
import app.epistola.valtimo.service.form.FormioFormGenerator;
import app.epistola.valtimo.service.completion.PollingCompletionEventConsumer;
import app.epistola.valtimo.service.suggestion.ProcessVariableDiscoveryService;
import app.epistola.valtimo.service.form.RetryFormService;
import app.epistola.valtimo.web.rest.EpistolaAdminResource;
import app.epistola.valtimo.web.rest.EpistolaCallbackResource;
import app.epistola.valtimo.web.rest.EpistolaGenerationResource;
import app.epistola.valtimo.web.rest.EpistolaTemplateResource;
import app.epistola.valtimo.web.rest.EpistolaToolingResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.plugin.service.PluginService;
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService;
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

import java.util.List;

@Slf4j
@AutoConfiguration
@ConditionalOnProperty(name = "epistola.enabled", havingValue = "true", matchIfMissing = true)
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
    @ConditionalOnMissingBean(ExpressionFunctionRegistry.class)
    public ExpressionFunctionRegistry expressionFunctionRegistry(
            List<EpistolaExpressionFunction> functions
    ) {
        return new ExpressionFunctionRegistry(functions);
    }

    @Bean
    @ConditionalOnMissingBean(FormatDateFunction.class)
    public FormatDateFunction formatDateFunction() {
        return new FormatDateFunction();
    }

    @Bean
    @ConditionalOnMissingBean(StringFunctions.class)
    public StringFunctions stringFunctions() {
        return new StringFunctions();
    }

    @Bean
    @ConditionalOnMissingBean(JsonataMappingService.class)
    public JsonataMappingService jsonataMappingService(
            ExpressionFunctionRegistry expressionFunctionRegistry
    ) {
        return new JsonataMappingService(expressionFunctionRegistry);
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
            JsonataMappingService jsonataMappingService,
            com.ritense.document.service.DocumentService documentService
    ) {
        return new EpistolaPluginFactory(pluginService, epistolaService, valueResolverService,
                objectMapper, jsonataMappingService, documentService);
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
            JsonataMappingService jsonataMappingService,
            com.ritense.document.service.DocumentService documentService,
            FormioFormGenerator formioFormGenerator,
            ObjectMapper objectMapper
    ) {
        return new RetryFormService(pluginService, epistolaService, runtimeService,
                taskService, processLinkService, jsonataMappingService, documentService,
                formioFormGenerator, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(app.epistola.valtimo.service.preview.PreviewService.class)
    public app.epistola.valtimo.service.preview.PreviewService previewService(
            PluginService pluginService,
            EpistolaService epistolaService,
            ProcessLinkService processLinkService,
            com.ritense.valtimo.operaton.service.OperatonRepositoryService operatonRepositoryService,
            RuntimeService runtimeService,
            JsonataMappingService jsonataMappingService,
            com.ritense.document.service.DocumentService documentService,
            ObjectMapper objectMapper
    ) {
        return new app.epistola.valtimo.service.preview.PreviewService(pluginService, epistolaService,
                processLinkService, operatonRepositoryService, runtimeService,
                jsonataMappingService, documentService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(VariableSuggestionService.class)
    public VariableSuggestionService variableSuggestionService(
            com.ritense.document.service.DocumentDefinitionService documentDefinitionService,
            ProcessVariableDiscoveryService processVariableDiscoveryService
    ) {
        return new VariableSuggestionService(documentDefinitionService, processVariableDiscoveryService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaTemplateResource.class)
    public EpistolaTemplateResource epistolaTemplateResource(
            PluginService pluginService,
            EpistolaService epistolaService
    ) {
        return new EpistolaTemplateResource(pluginService, epistolaService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaGenerationResource.class)
    public EpistolaGenerationResource epistolaGenerationResource(
            PluginService pluginService,
            EpistolaService epistolaService,
            app.epistola.valtimo.service.preview.PreviewService previewService,
            RetryFormService retryFormService,
            JsonataMappingService jsonataMappingService,
            com.ritense.document.service.DocumentService documentService,
            ObjectMapper objectMapper
    ) {
        return new EpistolaGenerationResource(pluginService, epistolaService,
                previewService, retryFormService, jsonataMappingService,
                documentService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaToolingResource.class)
    public EpistolaToolingResource epistolaToolingResource(
            ProcessVariableDiscoveryService processVariableDiscoveryService,
            VariableSuggestionService variableSuggestionService,
            ExpressionFunctionRegistry expressionFunctionRegistry
    ) {
        return new EpistolaToolingResource(processVariableDiscoveryService,
                variableSuggestionService, expressionFunctionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaAdminService.class)
    public EpistolaAdminService epistolaAdminService(
            PluginService pluginService,
            EpistolaService epistolaService,
            ProcessLinkService processLinkService,
            RepositoryService repositoryService,
            RuntimeService runtimeService,
            ProcessDefinitionCaseDefinitionService processDefinitionCaseDefinitionService
    ) {
        return new EpistolaAdminService(pluginService, epistolaService, processLinkService,
                repositoryService, runtimeService, processDefinitionCaseDefinitionService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaAdminResource.class)
    public EpistolaAdminResource epistolaAdminResource(
            EpistolaAdminService adminService
    ) {
        return new EpistolaAdminResource(adminService);
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
    @ConditionalOnMissingBean(CatalogScanner.class)
    public CatalogScanner catalogScanner(ObjectMapper objectMapper) {
        return new CatalogScanner(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaCatalogSyncService.class)
    public EpistolaCatalogSyncService epistolaCatalogSyncService(
            CatalogScanner scanner,
            EpistolaService epistolaService
    ) {
        return new EpistolaCatalogSyncService(scanner, epistolaService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaCatalogSyncTrigger.class)
    public EpistolaCatalogSyncTrigger epistolaCatalogSyncTrigger(
            PluginService pluginService,
            EpistolaCatalogSyncService syncService
    ) {
        return new EpistolaCatalogSyncTrigger(pluginService, syncService);
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
