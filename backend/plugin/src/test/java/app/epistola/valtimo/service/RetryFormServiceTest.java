package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.EpistolaProcessVariables;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.plugin.service.PluginService;
import com.ritense.processlink.service.ProcessLinkService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.engine.task.TaskQuery;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryFormServiceTest {

    @Mock private PluginService pluginService;
    @Mock private EpistolaService epistolaService;
    @Mock private RuntimeService runtimeService;
    @Mock private TaskService taskService;
    @Mock private ProcessLinkService processLinkService;
    @Mock private DataMappingResolverService dataMappingResolverService;
    @Mock private FormioFormGenerator formioFormGenerator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RetryFormService retryFormService;

    private static final String PROCESS_INSTANCE_ID = "proc-123";
    private static final String PROCESS_DEFINITION_ID = "myProcess:1:456";
    private static final String ACTIVITY_ID = "generateDocTask";
    private static final String TEMPLATE_ID = "tmpl-abc";
    private static final String BUSINESS_KEY = "doc-789";
    private static final String BASE_URL = "https://api.epistola.app";
    private static final String API_KEY = "secret-key";
    private static final String TENANT_ID = "tenant-1";

    @BeforeEach
    void setUp() {
        retryFormService = new RetryFormService(
                pluginService,
                epistolaService,
                runtimeService,
                taskService,
                processLinkService,
                dataMappingResolverService,
                formioFormGenerator,
                objectMapper
        );
    }

    @Nested
    class GenerateRetryFormWithExplicitSourceActivityId {

        @Test
        void findsProcessLinkAndGeneratesForm() {
            // Arrange
            ProcessInstance processInstance = mockProcessInstanceLookup(BUSINESS_KEY);
            PluginProcessLink link = mockPluginProcessLink(ACTIVITY_ID, TEMPLATE_ID, Map.of("name", "doc:name"));
            mockProcessLinkServiceForActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID, link);
            mockPluginInstance(link);
            mockTaskQueryReturnsEmpty();

            TemplateDetails templateDetails = new TemplateDetails(TEMPLATE_ID, "Invoice",
                    List.of(new TemplateField("name", "name", "string",
                            TemplateField.FieldType.SCALAR, true, null, List.of())));
            when(epistolaService.getTemplateDetails(BASE_URL, API_KEY, TENANT_ID, TEMPLATE_ID))
                    .thenReturn(templateDetails);

            Map<String, Object> resolvedData = Map.of("name", "John Doe");
            when(dataMappingResolverService.resolveMapping(eq(BUSINESS_KEY), anyMap()))
                    .thenReturn(resolvedData);

            ObjectNode expectedForm = objectMapper.createObjectNode();
            expectedForm.put("display", "form");
            expectedForm.putArray("components");
            when(formioFormGenerator.generateForm(eq(templateDetails.fields()), eq(resolvedData)))
                    .thenReturn(expectedForm);

            // Act
            ObjectNode result = retryFormService.generateRetryForm(PROCESS_INSTANCE_ID, null, ACTIVITY_ID);

            // Assert
            assertThat(result).isSameAs(expectedForm);
        }
    }

    @Nested
    class AutoDiscoverSingleGenerateDocumentActivity {

        @Test
        void worksWithSingleGenerateDocumentLink() {
            // Arrange
            mockProcessInstanceLookup(BUSINESS_KEY);
            PluginProcessLink link = mockPluginProcessLink(ACTIVITY_ID, TEMPLATE_ID, Map.of());
            mockTaskQueryReturnsEmpty();

            // No link found for explicit activity lookup — fall through to auto-discover
            when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID))
                    .thenReturn(List.of(link));
            mockPluginInstance(link);

            TemplateDetails templateDetails = new TemplateDetails(TEMPLATE_ID, "Invoice", List.of());
            when(epistolaService.getTemplateDetails(BASE_URL, API_KEY, TENANT_ID, TEMPLATE_ID))
                    .thenReturn(templateDetails);
            when(dataMappingResolverService.resolveMapping(eq(BUSINESS_KEY), anyMap()))
                    .thenReturn(Map.of());

            ObjectNode expectedForm = objectMapper.createObjectNode();
            expectedForm.put("display", "form");
            expectedForm.putArray("components");
            when(formioFormGenerator.generateForm(anyList(), anyMap()))
                    .thenReturn(expectedForm);

            // Act — no sourceActivityId provided
            ObjectNode result = retryFormService.generateRetryForm(PROCESS_INSTANCE_ID, null, null);

            // Assert
            assertThat(result).isSameAs(expectedForm);
        }
    }

    @Nested
    class MultipleGenerateDocumentActivities {

        @Test
        void throwsAmbiguousActivityWhenMultipleLinksFoundWithoutSourceActivityId() {
            // Arrange
            mockProcessInstanceLookup(BUSINESS_KEY);
            mockTaskQueryReturnsEmpty();

            PluginProcessLink link1 = mockPluginProcessLink("task1", TEMPLATE_ID, Map.of());
            PluginProcessLink link2 = mockPluginProcessLink("task2", TEMPLATE_ID, Map.of());

            when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID))
                    .thenReturn(List.of(link1, link2));

            // Act & Assert
            assertThatThrownBy(() -> retryFormService.generateRetryForm(PROCESS_INSTANCE_ID, null, null))
                    .isInstanceOf(RetryFormService.RetryFormException.class)
                    .satisfies(ex -> assertThat(((RetryFormService.RetryFormException) ex).getReason())
                            .isEqualTo(RetryFormService.RetryFormException.Reason.AMBIGUOUS_ACTIVITY));
        }
    }

    @Nested
    class NoGenerateDocumentActivities {

        @Test
        void throwsLinkNotFoundWhenNoLinksExist() {
            // Arrange
            mockProcessInstanceLookup(BUSINESS_KEY);
            mockTaskQueryReturnsEmpty();

            when(processLinkService.getProcessLinks(PROCESS_DEFINITION_ID))
                    .thenReturn(List.of());

            // Act & Assert
            assertThatThrownBy(() -> retryFormService.generateRetryForm(PROCESS_INSTANCE_ID, null, null))
                    .isInstanceOf(RetryFormService.RetryFormException.class)
                    .satisfies(ex -> assertThat(((RetryFormService.RetryFormException) ex).getReason())
                            .isEqualTo(RetryFormService.RetryFormException.Reason.LINK_NOT_FOUND));
        }
    }

    @Nested
    class ProcessInstanceNotFound {

        @Test
        void throwsProcessNotFoundWhenQueryReturnsNull() {
            // Arrange
            ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
            when(runtimeService.createProcessInstanceQuery()).thenReturn(query);
            when(query.processInstanceId(PROCESS_INSTANCE_ID)).thenReturn(query);
            when(query.singleResult()).thenReturn(null);

            // Act & Assert
            assertThatThrownBy(() -> retryFormService.generateRetryForm(PROCESS_INSTANCE_ID, null, ACTIVITY_ID))
                    .isInstanceOf(RetryFormService.RetryFormException.class)
                    .satisfies(ex -> assertThat(((RetryFormService.RetryFormException) ex).getReason())
                            .isEqualTo(RetryFormService.RetryFormException.Reason.PROCESS_NOT_FOUND));
        }
    }

    @Nested
    class MissingTemplateId {

        @Test
        void throwsMissingTemplateWhenTemplateIdAbsentInActionProperties() {
            // Arrange
            mockProcessInstanceLookup(BUSINESS_KEY);
            mockTaskQueryReturnsEmpty();

            // Create a link without templateId
            PluginProcessLink link = mockPluginProcessLink(ACTIVITY_ID, null, Map.of());
            mockProcessLinkServiceForActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID, link);

            // Act & Assert
            assertThatThrownBy(() -> retryFormService.generateRetryForm(PROCESS_INSTANCE_ID, null, ACTIVITY_ID))
                    .isInstanceOf(RetryFormService.RetryFormException.class)
                    .satisfies(ex -> assertThat(((RetryFormService.RetryFormException) ex).getReason())
                            .isEqualTo(RetryFormService.RetryFormException.Reason.MISSING_TEMPLATE));
        }
    }

    @Nested
    class NoDocumentIdAvailable {

        @Test
        void throwsNoDocumentIdWhenBusinessKeyIsNullAndNoExplicitDocumentId() {
            // Arrange — process instance with null business key
            mockProcessInstanceLookup(null);
            mockTaskQueryReturnsEmpty();

            PluginProcessLink link = mockPluginProcessLink(ACTIVITY_ID, TEMPLATE_ID, Map.of());
            mockProcessLinkServiceForActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID, link);

            // Act & Assert — no explicit documentId passed either
            assertThatThrownBy(() -> retryFormService.generateRetryForm(PROCESS_INSTANCE_ID, null, ACTIVITY_ID))
                    .isInstanceOf(RetryFormService.RetryFormException.class)
                    .satisfies(ex -> assertThat(((RetryFormService.RetryFormException) ex).getReason())
                            .isEqualTo(RetryFormService.RetryFormException.Reason.NO_DOCUMENT_ID));
        }
    }

    @Nested
    class SourceActivityFromActiveTaskLocalVariable {

        @Test
        void usesSourceActivityIdFromTaskLocalVariable() {
            // Arrange
            mockProcessInstanceLookup(BUSINESS_KEY);

            // Set up active task with local variable
            Task task = mock(Task.class);
            when(task.getId()).thenReturn("task-001");
            TaskQuery taskQuery = mock(TaskQuery.class);
            when(taskService.createTaskQuery()).thenReturn(taskQuery);
            when(taskQuery.processInstanceId(PROCESS_INSTANCE_ID)).thenReturn(taskQuery);
            when(taskQuery.active()).thenReturn(taskQuery);
            when(taskQuery.list()).thenReturn(List.of(task));
            when(taskService.getVariableLocal("task-001", EpistolaProcessVariables.SOURCE_ACTIVITY_ID))
                    .thenReturn(ACTIVITY_ID);

            PluginProcessLink link = mockPluginProcessLink(ACTIVITY_ID, TEMPLATE_ID, Map.of());
            mockProcessLinkServiceForActivity(PROCESS_DEFINITION_ID, ACTIVITY_ID, link);
            mockPluginInstance(link);

            TemplateDetails templateDetails = new TemplateDetails(TEMPLATE_ID, "Invoice", List.of());
            when(epistolaService.getTemplateDetails(BASE_URL, API_KEY, TENANT_ID, TEMPLATE_ID))
                    .thenReturn(templateDetails);
            when(dataMappingResolverService.resolveMapping(eq(BUSINESS_KEY), anyMap()))
                    .thenReturn(Map.of());

            ObjectNode expectedForm = objectMapper.createObjectNode();
            expectedForm.put("display", "form");
            expectedForm.putArray("components");
            when(formioFormGenerator.generateForm(anyList(), anyMap()))
                    .thenReturn(expectedForm);

            // Act — no sourceActivityId argument, should discover from task variable
            ObjectNode result = retryFormService.generateRetryForm(PROCESS_INSTANCE_ID, null, null);

            // Assert
            assertThat(result).isSameAs(expectedForm);
        }
    }

    // ---- Helper methods ----

    private ProcessInstance mockProcessInstanceLookup(String businessKey) {
        ProcessInstance processInstance = mock(ProcessInstance.class);
        lenient().when(processInstance.getProcessDefinitionId()).thenReturn(PROCESS_DEFINITION_ID);
        lenient().when(processInstance.getBusinessKey()).thenReturn(businessKey);
        lenient().when(processInstance.getId()).thenReturn(PROCESS_INSTANCE_ID);

        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(query);
        when(query.processInstanceId(PROCESS_INSTANCE_ID)).thenReturn(query);
        when(query.singleResult()).thenReturn(processInstance);

        return processInstance;
    }

    private PluginProcessLink mockPluginProcessLink(String activityId, String templateId, Map<String, Object> dataMapping) {
        PluginProcessLink link = mock(PluginProcessLink.class);
        lenient().when(link.getActivityId()).thenReturn(activityId);
        lenient().when(link.getPluginActionDefinitionKey()).thenReturn("generate-document");

        ObjectNode actionProps = objectMapper.createObjectNode();
        if (templateId != null) {
            actionProps.put("templateId", templateId);
        }
        if (dataMapping != null && !dataMapping.isEmpty()) {
            actionProps.set("dataMapping", objectMapper.valueToTree(dataMapping));
        }
        lenient().when(link.getActionProperties()).thenReturn(actionProps);

        // Use a mock PluginConfigurationId — the type is opaque to our test
        lenient().when(link.getPluginConfigurationId()).thenReturn(mock());

        return link;
    }

    private void mockProcessLinkServiceForActivity(String processDefinitionId, String activityId, PluginProcessLink link) {
        when(processLinkService.getProcessLinks(processDefinitionId, activityId))
                .thenReturn(List.of(link));
    }

    private void mockPluginInstance(PluginProcessLink link) {
        EpistolaPlugin plugin = mock(EpistolaPlugin.class);
        lenient().when(plugin.getBaseUrl()).thenReturn(BASE_URL);
        lenient().when(plugin.getApiKey()).thenReturn(API_KEY);
        lenient().when(plugin.getTenantId()).thenReturn(TENANT_ID);
        lenient().when(pluginService.createInstance(link.getPluginConfigurationId())).thenReturn(plugin);
    }

    private void mockTaskQueryReturnsEmpty() {
        TaskQuery taskQuery = mock(TaskQuery.class);
        lenient().when(taskService.createTaskQuery()).thenReturn(taskQuery);
        lenient().when(taskQuery.processInstanceId(PROCESS_INSTANCE_ID)).thenReturn(taskQuery);
        lenient().when(taskQuery.active()).thenReturn(taskQuery);
        lenient().when(taskQuery.list()).thenReturn(List.of());
    }
}
