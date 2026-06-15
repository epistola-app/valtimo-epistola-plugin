package app.epistola.valtimo.deployment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers {@link EpistolaCatchEventParseListener} on the process engine via Camunda's sanctioned
 * {@code ProcessEnginePlugin} SPI. Exposed as a Spring bean so Valtimo/Operaton's Spring Boot
 * integration collects and applies it during engine bootstrap.
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaProcessEnginePlugin extends AbstractProcessEnginePlugin {

    private final EpistolaCatchEventParseListener catchEventParseListener;

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        List<BpmnParseListener> listeners = configuration.getCustomPostBPMNParseListeners();
        if (listeners == null) {
            listeners = new ArrayList<>();
            configuration.setCustomPostBPMNParseListeners(listeners);
        }
        listeners.add(catchEventParseListener);
        log.info("Registered EpistolaCatchEventParseListener (auto-wires EpistolaDocumentGenerated catch events)");
    }
}
