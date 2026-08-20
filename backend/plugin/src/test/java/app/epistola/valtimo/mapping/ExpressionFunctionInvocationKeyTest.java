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
package app.epistola.valtimo.mapping;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionFunctionInvocationKeyTest {

    private static final Method METHOD;

    static {
        try {
            METHOD = ExpressionFunctionInvocationKeyTest.class.getDeclaredMethod("fixture", Object.class);
        } catch (NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Test
    void snapshotsNestedJsonLikeArgumentsStructurally() {
        var first = key(Map.of(
                "names", List.of("alpha", "beta"),
                "scores", new int[]{1, 2}));
        var second = key(Map.of(
                "scores", new int[]{1, 2},
                "names", List.of("alpha", "beta")));

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void mutationsAfterKeyCreationDoNotInvalidateTheKey() {
        List<String> tags = new ArrayList<>(List.of("priority"));
        Map<String, Object> argument = new LinkedHashMap<>();
        argument.put("tags", tags);
        var beforeMutation = key(argument);
        int originalHashCode = beforeMutation.hashCode();

        tags.add("digital");
        argument.put("status", "active");

        assertThat(beforeMutation.hashCode()).isEqualTo(originalHashCode);
        assertThat(beforeMutation).isEqualTo(key(Map.of("tags", List.of("priority"))));
    }

    @Test
    void distinguishesArraysFromIterables() {
        assertThat(key(new String[]{"value"})).isNotEqualTo(key(List.of("value")));
    }

    @Test
    void usesStableIdentityForUnknownMutableObjects() {
        MutableArgument argument = new MutableArgument("before");
        var first = key(argument);
        int originalHashCode = first.hashCode();

        argument.value = "after";

        assertThat(first.hashCode()).isEqualTo(originalHashCode);
        assertThat(first).isEqualTo(key(argument));
        assertThat(first).isNotEqualTo(key(new MutableArgument("after")));
    }

    @Test
    void handlesCyclicContainersWithoutRecursingForever() {
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);

        assertThat(key(cyclic)).isEqualTo(key(cyclic));
    }

    private static ExpressionFunctionInvocationKey key(Object argument) {
        return ExpressionFunctionInvocationKey.of("fixture", METHOD, new Object[]{argument});
    }

    @SuppressWarnings("unused")
    private static void fixture(Object value) {
    }

    private static final class MutableArgument {
        private String value;

        private MutableArgument(String value) {
            this.value = value;
        }
    }
}
