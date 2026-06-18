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
package app.epistola.valtimo.expression.functions;

import app.epistola.valtimo.expression.DefaultExpressionContext;
import app.epistola.valtimo.expression.ExpressionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StringFunctionsTest {

    private StringFunctions fn;
    private ExpressionContext ctx;

    @BeforeEach
    void setUp() {
        fn = new StringFunctions();
        ctx = new DefaultExpressionContext(null, null, Map.of(), Map.of(), Map.of());
    }

    @Test
    void shouldReturnCorrectNameAndDescription() {
        assertEquals("str", fn.name());
        assertNotNull(fn.description());
    }

    @Test
    void shouldConvertToString() {
        assertEquals("42", fn.execute(ctx, (Object) 42));
        assertEquals("true", fn.execute(ctx, (Object) true));
        assertEquals("hello", fn.execute(ctx, (Object) "hello"));
    }

    @Test
    void shouldReturnEmptyStringForNull() {
        assertEquals("", fn.execute(ctx, (Object) null));
    }

    @Test
    void shouldConcatenateTwoStrings() {
        assertEquals("HelloWorld", fn.execute(ctx, "Hello", "World"));
    }

    @Test
    void shouldConcatenateThreeStrings() {
        assertEquals("John Doe", fn.execute(ctx, "John", " ", "Doe"));
    }

    @Test
    void shouldHandleNullInConcatenation() {
        assertEquals("Hello", fn.execute(ctx, "Hello", (String) null));
        assertEquals("World", fn.execute(ctx, (String) null, "World"));
    }

    @Test
    void shouldReturnFallbackForNull() {
        assertEquals("default", fn.execute(ctx, null, (Object) "default"));
    }

    @Test
    void shouldReturnValueWhenNotNull() {
        assertEquals("actual", fn.execute(ctx, (Object) "actual", (Object) "default"));
    }
}
