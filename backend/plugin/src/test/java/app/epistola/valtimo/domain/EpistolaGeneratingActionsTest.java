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
package app.epistola.valtimo.domain;

import com.ritense.plugin.annotation.PluginAction;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link EpistolaProcessVariables#GENERATING_ACTION_KEYS} against the plugin class.
 *
 * <p>Three things reason about "a task that generates a document" — the catch-event correlation
 * that gives a wait its token, the deployment validator that warns about a generate with no
 * reachable wait, and the admin page's usage overview. A generating action missing from the list
 * is not a compile error and not a test failure anywhere else: the letter composer's action was
 * added without it, and the result was a letter that generated perfectly and then left its process
 * waiting for a message nothing would ever send.
 *
 * <p>So the list is derived here rather than reviewed. A generating action is recognised by its
 * method name — every one of them is {@code generate…} — which is a convention, but a visible one:
 * an action that generates and is not named for it fails this test and gets either a rename or an
 * explicit entry, and either way someone looks at the list.
 */
class EpistolaGeneratingActionsTest {

    private Set<String> declaredGeneratingActionKeys() {
        return Arrays.stream(EpistolaPlugin.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PluginAction.class))
                .filter(method -> method.getName().startsWith("generate"))
                .map(method -> method.getAnnotation(PluginAction.class).key())
                .collect(Collectors.toSet());
    }

    @Test
    void everyGeneratingActionIsKnownToTheThingsThatWaitOnIt() {
        assertThat(EpistolaProcessVariables.GENERATING_ACTION_KEYS)
                .describedAs(
                        "A plugin action that submits a generation job must be in "
                                + "EpistolaProcessVariables.GENERATING_ACTION_KEYS, or its catch event is "
                                + "never given a correlation token and the process waits forever.")
                .containsExactlyInAnyOrderElementsOf(declaredGeneratingActionKeys());
    }

    @Test
    void theListIsNotEmpty() {
        // Guards the reflection itself: a rename that made the filter match nothing would
        // otherwise turn the test above into a tautology.
        assertThat(declaredGeneratingActionKeys()).isNotEmpty();
    }

    /** Both are needed, and naming them keeps the failure above readable. */
    @Test
    void bothKnownGeneratingActionsAreListed() {
        assertThat(EpistolaProcessVariables.GENERATING_ACTION_KEYS)
                .contains("epistola-generate-document", "epistola-generate-dynamic-document");
    }
}
