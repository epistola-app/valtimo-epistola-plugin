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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Epistola's own rich-text schemas, to hold every converter's output against: read from
 * {@code app.epistola.contract:epistola-catalog} (a test dependency at the version the client is
 * pinned to), so a contract bump re-tests the converters against the schemas it brings.
 */
final class RichTextSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012());
    private static final Schema INLINE = load("richtext-inline-v1.json");
    private static final Schema BLOCK = load("richtext-block-v1.json");

    private RichTextSchemas() {
    }

    /** Assert the document is one Epistola accepts for a field of this kind, and return it. */
    static Map<String, Object> assertValid(Map<String, Object> document, RichTextKind kind) {
        List<Error> errors = (kind == RichTextKind.INLINE ? INLINE : BLOCK)
                .validate(MAPPER.valueToTree(document));
        assertThat(errors).as("%s rich text %s", kind, document).isEmpty();
        return document;
    }

    private static Schema load(String name) {
        try (InputStream input = RichTextSchemas.class.getResourceAsStream("/META-INF/epistola-catalog/schemas/" + name)) {
            if (input == null) {
                throw new IllegalStateException(name + " is not on the test classpath; is epistola-catalog?");
            }
            return REGISTRY.getSchema(input);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load " + name, e);
        }
    }
}
