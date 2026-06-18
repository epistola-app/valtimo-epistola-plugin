/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
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
        log.debug("Registered EpistolaCatchEventParseListener (auto-wires EpistolaDocumentGenerated catch events)");
    }
}
