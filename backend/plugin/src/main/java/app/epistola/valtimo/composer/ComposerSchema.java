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
package app.epistola.valtimo.composer;

/**
 * The version the letter composer stamps on what it stores, and the rule for reading it back.
 *
 * <p>Two of the composer's structures outlive the code that wrote them. A component's settings live
 * in a form definition that is versioned with the case and may have been authored years earlier; a
 * composed letter lives on a process variable of an instance that can sit in the database for
 * months before the generate task runs. Either can therefore be read by a plugin older or newer
 * than the one that wrote it.
 *
 * <p>The rule is asymmetric on purpose:
 *
 * <ul>
 *   <li><b>Older or absent is read.</b> Everything written before this field existed is version 1,
 *       so an upgrade never invalidates a stored letter or a deployed form.</li>
 *   <li><b>Newer is refused, loudly.</b> A structure from a later plugin may mean something this
 *       one would misread — silently generating the wrong letter is far worse than refusing — so
 *       it fails with a message naming both versions.</li>
 * </ul>
 *
 * <p>The composer is alpha and its structures may still change. This is what makes changing them
 * safe: raise {@link #CURRENT}, and anything written by the newer plugin is refused by the older
 * one instead of being half-understood.
 */
public final class ComposerSchema {

    /**
     * The version this plugin writes and is the highest it reads.
     *
     * <p>1 — the original: a component's settings ({@code letterSet} + baseline mapping) and a
     * composed letter ({@code catalogId}, {@code templateId}, {@code data}, {@code inputs}).
     */
    public static final int CURRENT = 1;

    /** The field both structures carry, and the one Epistola's own catalogs use. */
    public static final String FIELD = "schemaVersion";

    private ComposerSchema() {
    }

    /**
     * The effective version of something read back, refusing anything this plugin cannot read.
     *
     * @param declared what the structure carries, or null when it predates the field
     * @param what     how to name the structure in the error, e.g. "letter composer 'pv:brief'"
     * @return the version to read it as; 1 when nothing was declared
     */
    public static int readable(Integer declared, String what) {
        if (declared == null) {
            return 1;
        }
        if (declared > CURRENT) {
            throw new ComposerException(ComposerException.Reason.UNSUPPORTED_SCHEMA,
                    what + " was written for letter-composer schema " + declared
                            + ", but this plugin reads up to " + CURRENT
                            + ". Upgrade the Epistola plugin on this environment.");
        }
        if (declared < 1) {
            throw new ComposerException(ComposerException.Reason.UNSUPPORTED_SCHEMA,
                    what + " declares letter-composer schema " + declared + ", which is not a version.");
        }
        return declared;
    }
}
