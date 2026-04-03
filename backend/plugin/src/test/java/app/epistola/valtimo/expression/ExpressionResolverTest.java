package app.epistola.valtimo.expression;

import app.epistola.valtimo.expression.functions.FormatDateFunction;
import app.epistola.valtimo.expression.functions.StringFunctions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionResolverTest {

    private ExpressionResolver resolver;

    @BeforeEach
    void setUp() {
        ExpressionFunctionRegistry registry = new ExpressionFunctionRegistry(List.of(
                new FormatDateFunction(),
                new StringFunctions()
        ));
        resolver = new ExpressionResolver(registry);
    }

    private ExpressionContext contextWithDoc(Map<String, Object> docData) {
        return new DefaultExpressionContext(null, null, docData, Map.of(), Map.of());
    }

    private ExpressionContext contextWithDocAndPv(Map<String, Object> docData, Map<String, Object> pvData) {
        return new DefaultExpressionContext(null, null, docData, pvData, Map.of());
    }

    @Test
    void shouldDetectExpressionValues() {
        assertTrue(ExpressionResolver.isExpressionValue("expr:formatDate(#doc['date'], 'dd-MM-yyyy')"));
        assertFalse(ExpressionResolver.isExpressionValue("doc:customer.name"));
        assertFalse(ExpressionResolver.isExpressionValue("pv:someVar"));
        assertFalse(ExpressionResolver.isExpressionValue(null));
    }

    @Test
    void shouldResolveFormatDateWithStringLiterals() {
        ExpressionContext ctx = contextWithDoc(Map.of());
        Object result = resolver.resolve("expr:formatDate('2024-01-15', 'dd-MM-yyyy')", ctx);
        assertEquals("15-01-2024", result);
    }

    @Test
    void shouldResolveFormatDateWithDocReference() {
        ExpressionContext ctx = contextWithDoc(Map.of("invoiceDate", "2024-06-30"));
        Object result = resolver.resolve("expr:formatDate(#doc['invoiceDate'], 'MMMM d, yyyy')", ctx);
        assertNotNull(result);
        // Should contain "June 30, 2024" (locale-dependent, but the pattern is clear)
        assertTrue(result.toString().contains("30"));
        assertTrue(result.toString().contains("2024"));
    }

    @Test
    void shouldResolveStringPassthrough() {
        ExpressionContext ctx = contextWithDoc(Map.of("name", "John"));
        Object result = resolver.resolve("expr:str(#doc['name'])", ctx);
        assertEquals("John", result);
    }

    @Test
    void shouldResolveStringConcatenation() {
        ExpressionContext ctx = contextWithDoc(Map.of("first", "John", "last", "Doe"));
        Object result = resolver.resolve("expr:str(#doc['first'], ' ', #doc['last'])", ctx);
        assertEquals("John Doe", result);
    }

    @Test
    void shouldResolveWithProcessVariables() {
        ExpressionContext ctx = contextWithDocAndPv(Map.of(), Map.of("dateStr", "2024-12-25"));
        Object result = resolver.resolve("expr:formatDate(#pv['dateStr'], 'dd/MM/yyyy')", ctx);
        assertEquals("25/12/2024", result);
    }

    @Test
    void shouldResolveNoArgFunction() {
        // str with single arg is a passthrough
        ExpressionContext ctx = contextWithDoc(Map.of());
        Object result = resolver.resolve("expr:str('hello')", ctx);
        assertEquals("hello", result);
    }

    @Test
    void shouldThrowForUnknownFunction() {
        ExpressionContext ctx = contextWithDoc(Map.of());
        ExpressionEvaluationException ex = assertThrows(ExpressionEvaluationException.class, () ->
                resolver.resolve("expr:unknown('test')", ctx));
        assertTrue(ex.getMessage().contains("Unknown expression function"));
    }

    @Test
    void shouldThrowForMalformedExpression() {
        ExpressionContext ctx = contextWithDoc(Map.of());
        assertThrows(ExpressionEvaluationException.class, () ->
                resolver.resolve("expr:noParens", ctx));
    }

    @Test
    void shouldThrowForNonExpressionValue() {
        ExpressionContext ctx = contextWithDoc(Map.of());
        assertThrows(ExpressionEvaluationException.class, () ->
                resolver.resolve("doc:something", ctx));
    }

    @Test
    void shouldHandleEmptyArguments() {
        // StringFunctions doesn't have a zero-arg execute, should fail with overload mismatch
        ExpressionContext ctx = contextWithDoc(Map.of());
        assertThrows(ExpressionEvaluationException.class, () ->
                resolver.resolve("expr:str()", ctx));
    }

    @Test
    void shouldHandleNumericArguments() {
        ExpressionContext ctx = contextWithDoc(Map.of());
        Object result = resolver.resolve("expr:str(42)", ctx);
        assertEquals("42", result);
    }

    @Test
    void shouldHandleFormatDateWithDateTimeLiteral() {
        ExpressionContext ctx = contextWithDoc(Map.of());
        Object result = resolver.resolve("expr:formatDate('2024-06-15T14:30:00', 'dd-MM-yyyy HH:mm')", ctx);
        assertEquals("15-06-2024 14:30", result);
    }
}
