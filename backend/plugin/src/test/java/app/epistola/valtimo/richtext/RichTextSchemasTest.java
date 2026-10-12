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

import static app.epistola.valtimo.richtext.Docs.doc;
import static app.epistola.valtimo.richtext.Docs.p;
import static app.epistola.valtimo.richtext.Docs.text;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema check every format test leans on would pass anything if the schemas failed to load
 * as intended. These prove it refuses what Epistola refuses.
 */
class RichTextSchemasTest {

    @Test
    void refusesAMarkTheSchemaDoesNotHave() {
        assertThatThrownBy(() -> RichTextSchemas.assertValid(
                doc(p(text("x", Map.of("type", "code")))), RichTextKind.BLOCK))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void refusesAListItemThatDoesNotStartWithAParagraph() {
        Map<String, Object> nested = Map.of("type", "bullet_list", "content", List.of(
                Map.of("type", "list_item", "content", List.of(p(text("x"))))));
        assertThatThrownBy(() -> RichTextSchemas.assertValid(doc(Map.of("type", "bullet_list", "content",
                List.of(Map.of("type", "list_item", "content", List.of(nested))))), RichTextKind.BLOCK))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void refusesTwoParagraphsAsInlineRichText() {
        assertThatThrownBy(() -> RichTextSchemas.assertValid(doc(p(text("a")), p(text("b"))), RichTextKind.INLINE))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void refusesAColourInAnotherNotation() {
        assertThatThrownBy(() -> RichTextSchemas.assertValid(
                doc(p(text("x", Map.of("type", "textStyle", "attrs", Map.of("color", "red"))))), RichTextKind.BLOCK))
                .isInstanceOf(AssertionError.class);
    }
}
