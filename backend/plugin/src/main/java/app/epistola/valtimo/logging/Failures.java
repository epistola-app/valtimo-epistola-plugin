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
package app.epistola.valtimo.logging;

/**
 * Names a failure for a log line without quoting what it was working on.
 *
 * <p>A message from outside this plugin is not safe to log just because our own format is. Valtimo's
 * refused write reads {@code Failed to handle values … Values: {/besluit/x=gegrond}}; Jackson quotes
 * the text it could not parse. Where the thing being read or written is a case document, a form
 * submission, a letter or a generation request, that message is personal data, so these sites log
 * the exception's <em>type</em> instead — see the rule in {@code CLAUDE.md}.
 *
 * <p>A class name diagnoses less than a message. Naming the root cause as well as the outer one
 * recovers most of what matters, because that is usually where transient and permanent part company:
 * {@code EpistolaException caused by SocketTimeoutException} is a retry, the same exception
 * {@code caused by JsonParseException} is not.
 *
 * <p>Not every {@code e.getMessage()} in this plugin is a leak, and this does not try to replace
 * them all. A failure to list catalogs, resolve a process definition or read a bundled changelog
 * carries configuration, not case data, and there the message is worth keeping.
 */
public final class Failures {

    /** Deeper than any real wrapping chain, and a bound a cause cycle cannot outrun. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private Failures() {
    }

    /**
     * The failure's type, and its root cause's type when that differs — never a message.
     *
     * <p>Null-tolerant on purpose: this is called from catch blocks and log statements, where
     * throwing would replace a diagnostic with an incident.
     */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown cause";
        }
        Throwable root = failure;
        // Bounded, not just guarded against self-causation. Java refuses `x.initCause(x)`, but
        // nothing refuses `x.initCause(y); y.initCause(x)` — verified — and an unbounded walk spins
        // forever on that pair. A hang inside a catch block, on a request thread, is a far worse
        // failure than a shallow description, so the walk gives up rather than chases.
        for (int hop = 0; hop < MAX_CAUSE_DEPTH && root.getCause() != null && root.getCause() != root; hop++) {
            root = root.getCause();
        }
        return root == failure
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + " caused by " + root.getClass().getSimpleName();
    }
}
