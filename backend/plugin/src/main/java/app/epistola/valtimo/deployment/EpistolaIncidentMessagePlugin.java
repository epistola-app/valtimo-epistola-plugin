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

import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.jobexecutor.FailedJobCommandFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

/**
 * Makes an incident at an Epistola step say what the step said.
 * <p>
 * A step that cannot generate throws, and behind its async boundary the engine raises an incident
 * whose text is the message of what reached it (ADR 0009, third addendum). The plugin builds that
 * message to be the diagnosis: <em>"Template 'besluit' is not in catalog 'local/demo/brieven' in
 * release 2.0.0…"</em>. But Valtimo runs a process-link action by reflection from a Spring event
 * listener, and neither unwraps: the action's exception arrives inside an
 * {@link InvocationTargetException} inside an {@link UndeclaredThrowableException}, and the incident
 * read <em>"Failed to invoke event listener method"</em> with the diagnosis two causes down, in a
 * stack trace an administrator retrying incidents never opens.
 * <p>
 * So a failed job is recorded with the message the action threw, as if it had reached the engine
 * directly, and with the whole original chain as its cause, so the stored stack trace loses nothing.
 * Only those two wrappers are looked through, and only for an exception thrown out of the Epistola
 * plugin's own actions: another plugin's failure, or any other, is recorded exactly as before.
 * <p>
 * Nothing new reaches a log: the engine logs a failed job's stack trace, causes and all.
 */
public class EpistolaIncidentMessagePlugin extends AbstractProcessEnginePlugin {

    /** The class whose actions Valtimo invokes; an exception thrown out of one passes through it. */
    static final String ACTIONS = "com.ritense.valtimo.epistola.plugin.EpistolaPlugin";

    /** Wrappers are never nested deeper than this; a cycle or a longer chain is left alone. */
    private static final int MAX_WRAPPERS = 4;

    @Override
    public void postInit(ProcessEngineConfigurationImpl configuration) {
        // After init, when the engine has its own (or the installation's) factory to delegate to. The
        // engine asks the configuration for it each time a job fails, so replacing it here takes effect.
        configuration.setFailedJobCommandFactory(new Factory(configuration.getFailedJobCommandFactory()));
    }

    /** What the job is recorded as having failed with. */
    static Throwable asTheActionThrewIt(Throwable failure) {
        Throwable thrown = failure;
        for (int i = 0; i < MAX_WRAPPERS && isInvocationWrapper(thrown); i++) {
            thrown = thrown.getCause();
        }
        if (thrown == failure || thrown == null || isInvocationWrapper(thrown)
                || thrown.getMessage() == null || !thrownByAnAction(thrown)) {
            return failure;
        }
        return new ActionFailure(thrown.getMessage(), failure);
    }

    private static boolean isInvocationWrapper(Throwable t) {
        return (t instanceof UndeclaredThrowableException || t instanceof InvocationTargetException)
                && t.getCause() != null && t.getCause() != t;
    }

    private static boolean thrownByAnAction(Throwable t) {
        for (StackTraceElement frame : t.getStackTrace()) {
            if (ACTIONS.equals(frame.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /** Hands the engine's own factory the failure as the action threw it. */
    record Factory(FailedJobCommandFactory delegate) implements FailedJobCommandFactory {
        @Override
        public Command<Object> getCommand(String jobId, Throwable exception) {
            return delegate.getCommand(jobId, asTheActionThrewIt(exception));
        }
    }

    /** The action's message over the failure as it arrived. */
    static final class ActionFailure extends RuntimeException {
        ActionFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
