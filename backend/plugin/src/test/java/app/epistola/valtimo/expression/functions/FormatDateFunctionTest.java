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
