package app.epistola.valtimo.deployment;

import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers Epistola's BPMN parse customisations on the process engine. Exposed as a Spring bean so
 * Valtimo/Operaton's Spring Boot integration collects it and applies it during engine bootstrap.
 *
 * <p>Currently adds {@link EpistolaCatchEventTokenParseListener}, which attaches the per-branch
 * correlation-token wiring to {@code EpistolaDocumentGenerated} catch events.
 */
@Slf4j
public class EpistolaProcessEnginePlugin extends AbstractProcessEnginePlugin {

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        List<BpmnParseListener> listeners = configuration.getCustomPostBPMNParseListeners();
        if (listeners == null) {
            listeners = new ArrayList<>();
            configuration.setCustomPostBPMNParseListeners(listeners);
        }
        listeners.add(new EpistolaCatchEventTokenParseListener());
        log.info("Registered EpistolaCatchEventTokenParseListener as a custom post-BPMN parse listener");
    }
}
