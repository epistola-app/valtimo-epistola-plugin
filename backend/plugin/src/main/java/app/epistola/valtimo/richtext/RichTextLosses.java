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
package app.epistola.valtimo.richtext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a conversion could not carry into Epistola's document: a heading flattened to a paragraph, an
 * image left out, a mark the schema does not have.
 *
 * <p>It names <b>kinds</b> of things and counts them, and nothing else. The text they held is the
 * case's own and must never reach a log (CLAUDE.md, "Never log personal data"); "heading ×2" says
 * what an author needs to know, which is that the form offers more than the letter can show.
 */
public final class RichTextLosses {

    private final Map<String, Integer> counts = new LinkedHashMap<>();

    /** Record one thing of this kind that did not survive; {@code what} is a kind, never content. */
    public void lost(String what) {
        counts.merge(what, 1, Integer::sum);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    /** The kinds lost and how often, e.g. {@code heading ×2, image ×1}. */
    @Override
    public String toString() {
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " ×" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    Map<String, Integer> counts() {
        return Map.copyOf(counts);
    }
}
