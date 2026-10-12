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

import app.epistola.valtimo.domain.TemplateField.RichTextKind;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The editors available, by the format they write. A format nobody registered an editor for is
 * typed in a plain multi-line text area — right for any format a person types by hand.
 */
public class RichTextEditors {

    private final Map<String, RichTextEditor> byFormat = new TreeMap<>();

    /**
     * @throws IllegalStateException when two editors claim one format
     */
    public RichTextEditors(Collection<? extends RichTextEditor> editors) {
        for (RichTextEditor editor : editors) {
            RichTextEditor previous = byFormat.put(editor.formatId(), editor);
            if (previous != null) {
                throw new IllegalStateException("Two rich-text editors write format '" + editor.formatId() + "': "
                        + previous.getClass().getName() + " and " + editor.getClass().getName());
            }
        }
    }

    /** The component a person types {@code formatId} into, for a field of this kind. */
    public Map<String, Object> component(String formatId, RichTextKind kind) {
        RichTextEditor editor = byFormat.get(formatId);
        return editor != null ? new LinkedHashMap<>(editor.component(kind)) : textArea(kind);
    }

    private static Map<String, Object> textArea(RichTextKind kind) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "textarea");
        component.put("rows", kind == RichTextKind.INLINE ? 2 : 6);
        component.put("autoExpand", true);
        return component;
    }
}
