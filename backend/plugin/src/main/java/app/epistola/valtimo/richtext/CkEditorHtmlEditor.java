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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTML is typed in Form.io's text area in WYSIWYG mode, with CKEditor, limited to what Epistola
 * renders: only its buttons on the toolbar, and the plugins for anything else removed, so headings,
 * tables, images or media cannot arrive by paste or autoformat either.
 *
 * <p>CKEditor rather than Quill because only CKEditor's toolbar can be narrowed. Form.io 4
 * deep-merges a component's Quill settings into its default Quill toolbar, index by index, so a
 * shorter toolbar keeps every default button beyond it (headings, fonts, images, video), while
 * CKEditor has no default toolbar to merge into. Form.io's CKEditor build has no underline,
 * strikethrough, sub- or superscript; those still convert when they arrive in HTML from elsewhere.
 */
public class CkEditorHtmlEditor implements RichTextEditor {

    /**
     * The plugins of Form.io's CKEditor build whose content Epistola cannot render. Hiding their
     * buttons is not enough: a loaded plugin still accepts its content from a paste, and
     * Autoformat turns "# " into a heading and "> " into a quote. Removed, they leave the editor
     * schema, so a paste is reduced to paragraphs as it arrives. {@code Font} stays because it
     * carries the colour picker; the background colour it also accepts is dropped on conversion.
     * A name a host's build lacks is ignored by CKEditor.
     */
    static final List<String> UNRENDERABLE_PLUGINS = List.of(
            "Heading", "BlockQuote", "Table", "TableToolbar", "Image", "ImageCaption", "ImageStyle",
            "ImageToolbar", "ImageUpload", "ImageResize", "EasyImage", "CKFinder", "CKFinderUploadAdapter",
            "MediaEmbed", "Alignment", "IndentBlock", "FontFamily", "FontSize", "FontBackgroundColor");

    @Override
    public String formatId() {
        return HtmlFormat.ID;
    }

    @Override
    public Map<String, Object> component(RichTextKind kind) {
        List<String> toolbar = new ArrayList<>(List.of("bold", "italic", "|", "fontColor"));
        if (kind == RichTextKind.BLOCK) {
            toolbar.addAll(List.of("|", "bulletedList", "numberedList", "outdent", "indent"));
        }
        toolbar.addAll(List.of("|", "link", "|", "undo", "redo"));

        Map<String, Object> wysiwyg = new LinkedHashMap<>();
        wysiwyg.put("toolbar", toolbar);
        wysiwyg.put("removePlugins", UNRENDERABLE_PLUGINS);

        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "textarea");
        component.put("editor", "ckeditor");
        component.put("wysiwyg", wysiwyg);
        component.put("isUploadEnabled", false);
        component.put("rows", kind == RichTextKind.INLINE ? 2 : 6);
        return component;
    }
}
