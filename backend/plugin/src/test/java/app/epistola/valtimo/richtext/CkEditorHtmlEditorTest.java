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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CkEditorHtmlEditorTest {

    private final CkEditorHtmlEditor editor = new CkEditorHtmlEditor();

    @Test
    void offersAToolbarOfOnlyWhatEpistolaRenders() {
        Map<String, Object> block = editor.component(RichTextKind.BLOCK);
        Map<String, Object> inline = editor.component(RichTextKind.INLINE);

        assertThat(editor.formatId()).isEqualTo(HtmlFormat.ID);
        assertThat(block).containsEntry("type", "textarea").containsEntry("editor", "ckeditor");
        // Only buttons Form.io's CKEditor build has: an unknown one is a console warning, not a button.
        assertThat(block.get("wysiwyg")).isEqualTo(Map.of(
                "toolbar", List.of("bold", "italic", "|", "fontColor", "|", "bulletedList", "numberedList",
                        "outdent", "indent", "|", "link", "|", "undo", "redo"),
                // Hidden buttons are not enough: a loaded plugin still takes its content from a
                // paste, and Autoformat makes "# " a heading. Checked in a browser.
                "removePlugins", CkEditorHtmlEditor.UNRENDERABLE_PLUGINS));
        assertThat(CkEditorHtmlEditor.UNRENDERABLE_PLUGINS)
                .contains("Heading", "Table", "Image", "MediaEmbed", "BlockQuote")
                .doesNotContain("Font", "List", "Link", "Autoformat", "Indent");
        assertThat(String.valueOf(inline.get("wysiwyg"))).doesNotContain("List", "indent");
        assertThat(List.of(block, inline)).allSatisfy(component -> assertThat(component).containsEntry("isUploadEnabled", false));
    }

    @Test
    void aFormatWithoutAnEditorGetsAPlainTextArea() {
        var editors = new RichTextEditors(List.of(editor));

        assertThat(editors.component("markdown", RichTextKind.BLOCK))
                .containsEntry("type", "textarea").doesNotContainKey("editor");
        assertThat(editors.component("html", RichTextKind.BLOCK)).containsEntry("editor", "ckeditor");
    }

    @Test
    void refusesTwoEditorsForOneFormat() {
        assertThatThrownBy(() -> new RichTextEditors(List.of(editor, new CkEditorHtmlEditor())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'html'");
    }
}
