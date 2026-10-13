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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static app.epistola.valtimo.richtext.Docs.STRONG;
import static app.epistola.valtimo.richtext.Docs.doc;
import static app.epistola.valtimo.richtext.Docs.p;
import static app.epistola.valtimo.richtext.Docs.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProseMirrorFormatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProseMirrorFormat format = new ProseMirrorFormat(objectMapper);

    @Test
    void readsADocumentAsAnObjectOrAsItsJson() throws Exception {
        Map<String, Object> document = doc(p(text("vet", STRONG)));

        assertThat(format.toProseMirror(document, new RichTextLosses())).isEqualTo(document);
        assertThat(format.toProseMirror(objectMapper.writeValueAsString(document), new RichTextLosses()))
                .isEqualTo(document);
    }

    @Test
    void writesADocumentBackAsJsonText_whichAnEditorCanShow() throws Exception {
        Map<String, Object> document = doc(p(text("vet", STRONG)));

        Object written = format.fromProseMirror(document);

        // An object would show as "[object Object]" in a text area.
        assertThat(written).isInstanceOf(String.class);
        assertThat(objectMapper.readValue((String) written, Map.class)).isEqualTo(document);
    }

    @Test
    void refusesTextThatIsNotADocument_withoutQuotingIt() {
        assertThatThrownBy(() -> format.toProseMirror("BSN 123456782, geen JSON", new RichTextLosses()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("123456782");
    }
}
