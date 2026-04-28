package app.epistola.valtimo.mapping;

import app.epistola.valtimo.expression.ExpressionContext;
import app.epistola.valtimo.expression.ExpressionFunctionRegistry;
import app.epistola.valtimo.expression.EpistolaExpressionFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonataMappingServiceTest {

    private JsonataMappingService service;

    @BeforeEach
    void setUp() {
        ExpressionFunctionRegistry registry = new ExpressionFunctionRegistry(List.of());
        service = new JsonataMappingService(registry);
    }

    @Nested
    class SimpleFieldMappings {

        @Test
        void shouldMapDocumentFields() {
            String expression = """
                    { "name": $doc.customer.name, "email": $doc.customer.email }
                    """;

            Map<String, Object> doc = Map.of("customer",
                    Map.of("name", "John Doe", "email", "john@example.com"));

            Map<String, Object> result = service.evaluate(expression, doc, Map.of(), Map.of());

            assertThat(result).containsEntry("name", "John Doe");
            assertThat(result).containsEntry("email", "john@example.com");
        }

        @Test
        void shouldMapProcessVariables() {
            String expression = """
                    { "email": $pv.customerEmail, "status": $pv.approvalStatus }
                    """;

            Map<String, Object> pv = Map.of("customerEmail", "test@test.com", "approvalStatus", "approved");

            Map<String, Object> result = service.evaluate(expression, Map.of(), pv, Map.of());

            assertThat(result).containsEntry("email", "test@test.com");
            assertThat(result).containsEntry("status", "approved");
        }

        @Test
        void shouldSupportLiteralValues() {
            String expression = """
                    { "status": "active", "count": 42, "verified": true }
                    """;

            Map<String, Object> result = service.evaluate(expression, Map.of(), Map.of(), Map.of());

            assertThat(result).containsEntry("status", "active");
            assertThat(result).containsEntry("count", 42);
            assertThat(result).containsEntry("verified", true);
        }
    }

    @Nested
    class StringOperations {

        @Test
        void shouldConcatenateStrings() {
            String expression = """
                    { "fullName": $doc.firstName & " " & $doc.lastName }
                    """;

            Map<String, Object> doc = Map.of("firstName", "John", "lastName", "Doe");

            Map<String, Object> result = service.evaluate(expression, doc, Map.of(), Map.of());

            assertThat(result).containsEntry("fullName", "John Doe");
        }
    }

    @Nested
    class Conditionals {

        @Test
        void shouldEvaluateTernaryExpression() {
            String expression = """
                    { "type": $doc.isVip ? "premium" : "standard" }
                    """;

            Map<String, Object> doc = Map.of("isVip", true);
            Map<String, Object> result = service.evaluate(expression, doc, Map.of(), Map.of());
            assertThat(result).containsEntry("type", "premium");

            Map<String, Object> doc2 = Map.of("isVip", false);
            Map<String, Object> result2 = service.evaluate(expression, doc2, Map.of(), Map.of());
            assertThat(result2).containsEntry("type", "standard");
        }

        @Test
        void shouldEvaluateComparisonInConditional() {
            String expression = """
                    { "discount": $doc.total > 1000 ? 10 : 0 }
                    """;

            Map<String, Object> doc = Map.of("total", 1500);
            Map<String, Object> result = service.evaluate(expression, doc, Map.of(), Map.of());
            assertThat(result).containsEntry("discount", 10);
        }
    }

    @Nested
    class NestedObjects {

        @Test
        void shouldBuildNestedStructure() {
            String expression = """
                    {
                      "customer": {
                        "name": $doc.customer.name,
                        "address": {
                          "city": $doc.customer.city
                        }
                      }
                    }
                    """;

            Map<String, Object> doc = Map.of("customer",
                    Map.of("name", "Alice", "city", "Amsterdam"));

            Map<String, Object> result = service.evaluate(expression, doc, Map.of(), Map.of());

            assertThat(result).containsKey("customer");
            @SuppressWarnings("unchecked")
            Map<String, Object> customer = (Map<String, Object>) result.get("customer");
            assertThat(customer).containsEntry("name", "Alice");
            @SuppressWarnings("unchecked")
            Map<String, Object> address = (Map<String, Object>) customer.get("address");
            assertThat(address).containsEntry("city", "Amsterdam");
        }

        @Test
        void shouldPassthroughSubtreeFromVariable() {
            String expression = """
                    { "customer": $pv.enrichedCustomer }
                    """;

            Map<String, Object> pv = Map.of("enrichedCustomer",
                    Map.of("name", "Bob", "email", "bob@test.com"));

            Map<String, Object> result = service.evaluate(expression, Map.of(), pv, Map.of());

            @SuppressWarnings("unchecked")
            Map<String, Object> customer = (Map<String, Object>) result.get("customer");
            assertThat(customer).containsEntry("name", "Bob");
            assertThat(customer).containsEntry("email", "bob@test.com");
        }
    }

    @Nested
    class ArrayOperations {

        @Test
        void shouldPassthroughArray() {
            String expression = """
                    { "items": $pv.orderLines }
                    """;

            List<Map<String, Object>> items = List.of(
                    Map.of("product", "Widget", "price", 10),
                    Map.of("product", "Gadget", "price", 20));
            Map<String, Object> pv = Map.of("orderLines", items);

            Map<String, Object> result = service.evaluate(expression, Map.of(), pv, Map.of());

            assertThat(result).containsKey("items");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultItems = (List<Map<String, Object>>) result.get("items");
            assertThat(resultItems).hasSize(2);
        }

        @Test
        void shouldMapArrayFieldsWithProjection() {
            String expression = """
                    { "items": $pv.orderLines.{ "name": product, "total": price * quantity } }
                    """;

            List<Map<String, Object>> items = List.of(
                    Map.of("product", "Widget", "price", 10, "quantity", 5),
                    Map.of("product", "Gadget", "price", 20, "quantity", 3));
            Map<String, Object> pv = Map.of("orderLines", items);

            Map<String, Object> result = service.evaluate(expression, Map.of(), pv, Map.of());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultItems = (List<Map<String, Object>>) result.get("items");
            assertThat(resultItems).hasSize(2);
            assertThat(resultItems.get(0)).containsEntry("name", "Widget");
            assertThat(resultItems.get(0)).containsEntry("total", 50);
        }

        @Test
        void shouldFilterArrayWithPredicate() {
            String expression = """
                    { "active": $pv.items[status = "active"] }
                    """;

            List<Map<String, Object>> items = List.of(
                    Map.of("name", "A", "status", "active"),
                    Map.of("name", "B", "status", "inactive"),
                    Map.of("name", "C", "status", "active"));
            Map<String, Object> pv = Map.of("items", items);

            Map<String, Object> result = service.evaluate(expression, Map.of(), pv, Map.of());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> active = (List<Map<String, Object>>) result.get("active");
            assertThat(active).hasSize(2);
        }
    }

    @Nested
    class BuiltInFunctions {

        @Test
        void shouldUseStringFunction() {
            String expression = """
                    { "upper": $uppercase($doc.name) }
                    """;

            Map<String, Object> doc = Map.of("name", "hello");
            Map<String, Object> result = service.evaluate(expression, doc, Map.of(), Map.of());

            assertThat(result).containsEntry("upper", "HELLO");
        }

        @Test
        void shouldUseSumFunction() {
            String expression = """
                    { "total": $sum($pv.items.price) }
                    """;

            List<Map<String, Object>> items = List.of(
                    Map.of("price", 10), Map.of("price", 20), Map.of("price", 30));
            Map<String, Object> pv = Map.of("items", items);

            Map<String, Object> result = service.evaluate(expression, Map.of(), pv, Map.of());

            assertThat(result).containsEntry("total", 60);
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void shouldReturnEmptyMapForNullExpression() {
            Map<String, Object> result = service.evaluate(null, Map.of(), Map.of(), Map.of());
            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnEmptyMapForBlankExpression() {
            Map<String, Object> result = service.evaluate("  ", Map.of(), Map.of(), Map.of());
            assertThat(result).isEmpty();
        }

        @Test
        void shouldThrowWhenExpressionReturnsNonObject() {
            String expression = "\"just a string\"";

            assertThatThrownBy(() -> service.evaluate(expression, Map.of(), Map.of(), Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must return an object");
        }
    }

    @Nested
    class ScalarEvaluation {

        @Test
        void shouldEvaluateSimpleReference() {
            var ctx = EvaluationContext.builder()
                    .expression("$pv.language")
                    .processVariableResolver(Map.of("language", "nl")::get)
                    .build();

            assertThat(service.evaluateScalar(ctx)).isEqualTo("nl");
        }

        @Test
        void shouldEvaluateStringConcatenation() {
            var ctx = EvaluationContext.builder()
                    .expression("\"besluit-\" & $doc.lastName & \".pdf\"")
                    .documentResolver(id -> Map.of("lastName", "Jansen"))
                    .build();

            assertThat(service.evaluateScalar(ctx)).isEqualTo("besluit-Jansen.pdf");
        }

        @Test
        void shouldReturnNullForNullExpression() {
            var ctx = EvaluationContext.builder().expression(null).build();
            assertThat(service.evaluateScalar(ctx)).isNull();
        }

        @Test
        void shouldReturnBlankForBlankExpression() {
            var ctx = EvaluationContext.builder().expression("  ").build();
            assertThat(service.evaluateScalar(ctx)).isEqualTo("  ");
        }
    }

    @Nested
    class CustomFunctions {

        @BeforeEach
        void setUp() {
            // Register a test function
            EpistolaExpressionFunction greetFunc = new EpistolaExpressionFunction() {
                @Override
                public String name() { return "greet"; }

                @Override
                public String description() { return "Test greeting"; }

                @SuppressWarnings("unused")
                public String execute(ExpressionContext ctx, String name) {
                    return "Hello, " + name + "!";
                }
            };

            ExpressionFunctionRegistry registry = new ExpressionFunctionRegistry(List.of(greetFunc));
            service = new JsonataMappingService(registry);
        }

        @Test
        void shouldCallCustomFunction() {
            String expression = """
                    { "greeting": $greet($doc.name) }
                    """;

            Map<String, Object> doc = Map.of("name", "World");
            Map<String, Object> result = service.evaluate(expression, doc, Map.of(), Map.of());

            assertThat(result).containsEntry("greeting", "Hello, World!");
        }
    }
}
