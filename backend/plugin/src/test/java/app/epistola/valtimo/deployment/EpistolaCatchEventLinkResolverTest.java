package app.epistola.valtimo.deployment;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ritense.plugin.domain.PluginProcessLink;
import com.ritense.processlink.domain.ProcessLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import com.ritense.processlink.service.ProcessLinkService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link EpistolaCatchEventLinkResolver}: pairing a {@code generate-document} to its
 * reachable {@code EpistolaDocumentGenerated} catch event (public BPMN model) and exposing the link's
 * result-variable name. Uses a real parsed model + mocked {@code RepositoryService}/{@code ProcessLinkService}.
 */
class EpistolaCatchEventLinkResolverTest {

    private static final String DEF_ID = "process-def:1";

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
                              targetNamespace="http://bpmn.io/schema/bpmn" id="defs">
              <bpmn:message id="m" name="EpistolaDocumentGenerated" />
              <bpmn:process id="p" isExecutable="true">
                <bpmn:startEvent id="start"><bpmn:outgoing>f0</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f0" sourceRef="start" targetRef="generate-doc1" />
                <bpmn:serviceTask id="generate-doc1" camunda:expression="${null}">
                  <bpmn:incoming>f0</bpmn:incoming><bpmn:outgoing>f1</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f1" sourceRef="generate-doc1" targetRef="wait-doc1" />
                <bpmn:intermediateCatchEvent id="wait-doc1">
                  <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>
                  <bpmn:messageEventDefinition messageRef="m" />
                </bpmn:intermediateCatchEvent>
                <bpmn:sequenceFlow id="f2" sourceRef="wait-doc1" targetRef="end" />
                <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private RepositoryService repositoryService;
    private ProcessLinkService processLinkService;
    private EpistolaCatchEventLinkResolver resolver;

    @BeforeEach
    void setUp() {
        repositoryService = mock(RepositoryService.class);
        processLinkService = mock(ProcessLinkService.class);
        resolver = new EpistolaCatchEventLinkResolver(repositoryService, processLinkService);

        BpmnModelInstance model = Bpmn.readModelFromStream(new ByteArrayInputStream(BPMN.getBytes(StandardCharsets.UTF_8)));
        when(repositoryService.getBpmnModelInstance(DEF_ID)).thenReturn(model);
    }

    @Test
    void resolvesTheResultVariableOfTheGenerateDocumentReachingTheCatchEvent() {
        PluginProcessLink link = generateDocumentLink("generate-doc1", "requestId1");
        when(processLinkService.getProcessLinks(DEF_ID)).thenReturn(List.<ProcessLink>of(link));

        assertThat(resolver.resultVariableFor(DEF_ID, "wait-doc1")).isEqualTo("requestId1");
    }

    @Test
    void returnsNullForACatchEventNotTargetedByAGenerateDocument() {
        PluginProcessLink link = generateDocumentLink("generate-doc1", "requestId1");
        when(processLinkService.getProcessLinks(DEF_ID)).thenReturn(List.<ProcessLink>of(link));

        assertThat(resolver.resultVariableFor(DEF_ID, "some-other-catch-event")).isNull();
    }

    @Test
    void returnsNullWhenNoGenerateDocumentLinksExist() {
        when(processLinkService.getProcessLinks(DEF_ID)).thenReturn(List.of());

        assertThat(resolver.resultVariableFor(DEF_ID, "wait-doc1")).isNull();
    }

    private PluginProcessLink generateDocumentLink(String activityId, String resultProcessVariable) {
        ObjectNode props = JsonNodeFactory.instance.objectNode();
        props.put("resultProcessVariable", resultProcessVariable);
        PluginProcessLink link = mock(PluginProcessLink.class);
        when(link.getActivityId()).thenReturn(activityId);
        when(link.getPluginActionDefinitionKey()).thenReturn("epistola-generate-document");
        when(link.getActionProperties()).thenReturn(props);
        return link;
    }
}
