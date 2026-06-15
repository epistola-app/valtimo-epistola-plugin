package app.epistola.valtimo.config;

import app.epistola.valtimo.authorization.EpistolaAdministrationActionProvider;
import app.epistola.valtimo.authorization.EpistolaAdministrationSpecificationFactory;
import app.epistola.valtimo.client.EpistolaApiClientFactory;
import app.epistola.valtimo.deploy.CatalogScanner;
import app.epistola.valtimo.deploy.EpistolaCatalogSyncService;
import app.epistola.valtimo.deploy.EpistolaCatalogSyncTrigger;
import app.epistola.valtimo.deployment.EpistolaCatchEventLinkResolver;
import app.epistola.valtimo.deployment.EpistolaCatchEventParseListener;
import app.epistola.valtimo.deployment.EpistolaProcessDefinitionValidator;
import app.epistola.valtimo.deployment.EpistolaProcessEnginePlugin;
import app.epistola.valtimo.service.completion.EpistolaCatchEventStartListener;
import app.epistola.valtimo.expression.EpistolaExpressionFunction;
import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import app.epistola.valtimo.expression.functions.FormatDateFunction;
import app.epistola.valtimo.expression.functions.StringFunctions;
import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.admin.EpistolaAdminService;
import app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner;
import app.epistola.valtimo.service.suggestion.VariableSuggestionService;
import app.epistola.valtimo.service.completion.EpistolaMessageCorrelationService;
import app.epistola.valtimo.service.download.DocumentStorageStrategy;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.EpistolaServiceImpl;
import app.epistola.valtimo.service.form.FormioFormGenerator;
import app.epistola.valtimo.service.suggestion.ProcessVariableDiscoveryService;
import app.epistola.valtimo.service.form.RetryFormService;
import app.epistola.valtimo.web.rest.EpistolaAdminResource;
import app.epistola.valtimo.web.rest.EpistolaGenerationResource;
import app.epistola.valtimo.web.rest.EpistolaTemplateResource;
import app.epistola.valtimo.web.rest.EpistolaToolingResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.plugin.service.PluginService;
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.resource.autoconfigure.TemporaryResourceStorageAutoConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@Slf4j
@AutoConfiguration(after = TemporaryResourceStorageAutoConfiguration.class)
@ConditionalOnProperty(name = "epistola.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EpistolaProperties.class)
@EnableScheduling
@Import(EpistolaDownloadStorageConfiguration.class)
public class EpistolaPluginAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EpistolaApiClientFactory.class)
    public EpistolaApiClientFactory epistolaApiClientFactory() {
        return new EpistolaApiClientFactory();
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaCatchEventLinkResolver.class)
    public EpistolaCatchEventLinkResolver epistolaCatchEventLinkResolver(
            RepositoryService repositoryService,
            ProcessLinkService processLinkService
    ) {
        return new EpistolaCatchEventLinkResolver(repositoryService, processLinkService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaCatchEventStartListener.class)
    public EpistolaCatchEventStartListener epistolaCatchEventStartListener(
            // @Lazy breaks the startup cycle: this listener is reached (via the parse listener) from
            // the ProcessEnginePlugin that builds the engine, but its collaborators depend on engine
            // beans (RepositoryService/RuntimeService) and the ProcessLinkService graph. They are only
            // needed at runtime (catch-event entry), so inject lazy proxies and resolve on first use.
            @Lazy EpistolaCatchEventLinkResolver linkResolver,
            @Lazy EpistolaMessageCorrelationService correlationService
    ) {
        return new EpistolaCatchEventStartListener(linkResolver, correlationService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaCatchEventParseListener.class)
    public EpistolaCatchEventParseListener epistolaCatchEventParseListener(
            EpistolaCatchEventStartListener catchEventStartListener
    ) {
        return new EpistolaCatchEventParseListener(catchEventStartListener);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaProcessEnginePlugin.class)
    public EpistolaProcessEnginePlugin epistolaProcessEnginePlugin(
            EpistolaCatchEventParseListener catchEventParseListener
    ) {
        return new EpistolaProcessEnginePlugin(catchEventParseListener);
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
            ObjectMapper objectMapper,
            JsonataMappingService jsonataMappingService,
            com.ritense.document.service.DocumentService documentService,
            EpistolaResultCollectorRunner resultCollectorRunner,
            List<DocumentStorageStrategy> storageStrategies
    ) {
        return new EpistolaPluginFactory(pluginService, epistolaService,
                objectMapper, jsonataMappingService, documentService, resultCollectorRunner,
                storageStrategies);
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
            ObjectMapper objectMapper,
            com.ritense.authorization.AuthorizationService authorizationService,
            com.ritense.valtimo.service.OperatonTaskService operatonTaskService,
            RuntimeService runtimeService
    ) {
        return new EpistolaGenerationResource(pluginService, epistolaService,
                previewService, retryFormService, jsonataMappingService,
                documentService, objectMapper, authorizationService, operatonTaskService,
                runtimeService);
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
            EpistolaMessageCorrelationService correlationService,
            ProcessLinkService processLinkService,
            RepositoryService repositoryService,
            RuntimeService runtimeService,
            ProcessDefinitionCaseDefinitionService processDefinitionCaseDefinitionService,
            EpistolaProcessDefinitionValidator processDefinitionValidator,
            EpistolaCatalogSyncService catalogSyncService
    ) {
        return new EpistolaAdminService(pluginService, epistolaService, correlationService, processLinkService,
                repositoryService, runtimeService, processDefinitionCaseDefinitionService, processDefinitionValidator,
                catalogSyncService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaProcessDefinitionValidator.class)
    public EpistolaProcessDefinitionValidator epistolaProcessDefinitionValidator(
            RepositoryService repositoryService,
            ProcessLinkService processLinkService,
            TaskScheduler taskScheduler,
            @Value("${epistola.validator.cron:0 */10 * * * *}") String validatorCron,
            @Value("${epistola.validator.zone:UTC}") String validatorZone
    ) {
        return new EpistolaProcessDefinitionValidator(
                repositoryService, processLinkService, taskScheduler, validatorCron, validatorZone);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaAdminResource.class)
    public EpistolaAdminResource epistolaAdminResource(
            EpistolaAdminService adminService,
            com.ritense.authorization.AuthorizationService authorizationService
    ) {
        return new EpistolaAdminResource(adminService, authorizationService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaMessageCorrelationService.class)
    public EpistolaMessageCorrelationService epistolaMessageCorrelationService(
            RuntimeService runtimeService
    ) {
        return new EpistolaMessageCorrelationService(runtimeService);
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaResultCollectorRunner.class)
    public EpistolaResultCollectorRunner epistolaResultCollectorRunner(
            PluginService pluginService,
            EpistolaApiClientFactory apiClientFactory,
            EpistolaMessageCorrelationService correlationService,
            EpistolaProperties properties
    ) {
        return new EpistolaResultCollectorRunner(
                pluginService,
                apiClientFactory,
                correlationService,
                properties
        );
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaHttpSecurityConfigurer.class)
    public EpistolaHttpSecurityConfigurer epistolaHttpSecurityConfigurer() {
        return new EpistolaHttpSecurityConfigurer();
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaAdministrationActionProvider.class)
    public EpistolaAdministrationActionProvider epistolaAdministrationActionProvider() {
        return new EpistolaAdministrationActionProvider();
    }

    @Bean
    @ConditionalOnMissingBean(EpistolaAdministrationSpecificationFactory.class)
    public EpistolaAdministrationSpecificationFactory epistolaAdministrationSpecificationFactory() {
        return new EpistolaAdministrationSpecificationFactory();
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
