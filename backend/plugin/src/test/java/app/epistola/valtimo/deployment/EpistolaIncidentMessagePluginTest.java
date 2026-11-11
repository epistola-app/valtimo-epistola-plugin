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

import app.epistola.valtimo.service.DocumentGenerationRefusedException;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.jobexecutor.FailedJobCommandFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.atomic.AtomicReference;

import static app.epistola.valtimo.deployment.EpistolaIncidentMessagePlugin.asTheActionThrewIt;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a failed job is recorded as: the integration test drives it through a real engine, this pins
 * where it stops — only Valtimo's two invocation wrappers, only around an Epistola action.
 */
class EpistolaIncidentMessagePluginTest {

    private static final String DIAGNOSIS = "Template 'besluit' is not in catalog 'local/demo/brieven' in release 2.0.0.";

    @Test
    void anActionsFailure_isRecordedWithItsOwnMessage_andTheWholeChainAsCause() {
        RuntimeException refusal = thrownByAnAction(new DocumentGenerationRefusedException(DIAGNOSIS));
        Throwable asValtimoDeliversIt = asValtimoDeliversIt(refusal);

        Throwable recorded = asTheActionThrewIt(asValtimoDeliversIt);

        assertThat(recorded).hasMessage(DIAGNOSIS).hasCause(asValtimoDeliversIt).hasRootCause(refusal);
    }

    @Test
    void anotherPluginsFailure_isRecordedAsItArrived() {
        RuntimeException elsewhere = new IllegalStateException("Some other plugin failed");
        Throwable arrived = asValtimoDeliversIt(elsewhere);

        assertThat(asTheActionThrewIt(arrived)).isSameAs(arrived);
    }

    @Test
    void aFailureThatReachedTheEngineDirectly_isLeftAlone() {
        RuntimeException direct = thrownByAnAction(new DocumentGenerationRefusedException(DIAGNOSIS));

        assertThat(asTheActionThrewIt(direct)).isSameAs(direct);
    }

    @Test
    void aWrapperOverAnyOtherCause_isLeftAlone() {
        Throwable wrapped = new UndeclaredThrowableException(new java.io.IOException("disk"), "Failed to invoke event listener method");

        assertThat(asTheActionThrewIt(wrapped)).isSameAs(wrapped);
    }

    @Test
    void anActionsFailureWithoutAMessage_isRecordedAsItArrived() {
        Throwable arrived = asValtimoDeliversIt(thrownByAnAction(new NullPointerException()));

        assertThat(asTheActionThrewIt(arrived)).isSameAs(arrived);
    }

    @Test
    void theEnginesFactoryIsHandedTheActionsFailure() {
        AtomicReference<Throwable> handed = new AtomicReference<>();
        FailedJobCommandFactory engines = (jobId, exception) -> {
            handed.set(exception);
            return context -> null;
        };

        new EpistolaIncidentMessagePlugin.Factory(engines)
                .getCommand("job", asValtimoDeliversIt(thrownByAnAction(new DocumentGenerationRefusedException(DIAGNOSIS))));

        assertThat(handed.get()).hasMessage(DIAGNOSIS);
    }

    /** As Valtimo's reflective action call and Spring's event listener deliver it to the engine. */
    private static Throwable asValtimoDeliversIt(Throwable thrown) {
        return new UndeclaredThrowableException(new InvocationTargetException(thrown), "Failed to invoke event listener method");
    }

    private static <T extends RuntimeException> T thrownByAnAction(T exception) {
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement(EpistolaIncidentMessagePlugin.ACTIONS, "generateDocument", "EpistolaPlugin.java", 371)
        });
        return exception;
    }
}
