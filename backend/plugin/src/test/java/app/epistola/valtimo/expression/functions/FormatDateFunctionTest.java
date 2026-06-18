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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormatDateFunctionTest {

    private FormatDateFunction fn;
    private ExpressionContext ctx;

    @BeforeEach
    void setUp() {
        fn = new FormatDateFunction();
        ctx = new DefaultExpressionContext(null, null, Map.of(), Map.of(), Map.of());
    }

    @Test
    void shouldReturnCorrectNameAndDescription() {
        assertEquals("formatDate", fn.name());
        assertNotNull(fn.description());
    }

    @Test
    void shouldFormatLocalDate() {
        String result = fn.execute(ctx, LocalDate.of(2024, 1, 15), "dd-MM-yyyy");
        assertEquals("15-01-2024", result);
    }

    @Test
    void shouldFormatLocalDateWithDifferentPattern() {
        String result = fn.execute(ctx, LocalDate.of(2024, 6, 30), "yyyy/MM/dd");
        assertEquals("2024/06/30", result);
    }

    @Test
    void shouldFormatLocalDateTime() {
        String result = fn.execute(ctx, LocalDateTime.of(2024, 3, 15, 14, 30, 0), "dd-MM-yyyy HH:mm");
        assertEquals("15-03-2024 14:30", result);
    }

    @Test
    void shouldFormatIsoDateString() {
        String result = fn.execute(ctx, "2024-01-15", "dd/MM/yyyy");
        assertEquals("15/01/2024", result);
    }

    @Test
    void shouldFormatIsoDateTimeString() {
        String result = fn.execute(ctx, "2024-03-15T14:30:00", "dd-MM-yyyy HH:mm");
        assertEquals("15-03-2024 14:30", result);
    }

    @Test
    void shouldThrowForInvalidDateString() {
        assertThrows(Exception.class, () -> fn.execute(ctx, "not-a-date", "dd-MM-yyyy"));
    }

    @Test
    void shouldThrowForInvalidPattern() {
        assertThrows(Exception.class, () ->
                fn.execute(ctx, LocalDate.of(2024, 1, 1), "ZZZZZZ-invalid"));
    }
}
