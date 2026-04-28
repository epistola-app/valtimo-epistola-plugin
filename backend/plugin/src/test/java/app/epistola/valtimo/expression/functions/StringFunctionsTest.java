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
