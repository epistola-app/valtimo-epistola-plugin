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

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stable evaluation-cache key for a matched expression-function invocation.
 *
 * <p>JSON-like containers are copied recursively so function code cannot invalidate a
 * hash-map entry by mutating an input. Unknown objects use identity semantics: that may
 * produce a safe cache miss for equal DTO instances, but never an unsafe cache hit.</p>
 */
final class ExpressionFunctionInvocationKey {

    private final String functionName;
    private final Method method;
    private final List<Object> arguments;

    private ExpressionFunctionInvocationKey(String functionName, Method method, Object[] arguments) {
        this.functionName = functionName;
        this.method = method;
        IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();
        this.arguments = Arrays.stream(arguments)
                .map(argument -> snapshot(argument, visiting))
                .toList();
    }

    static ExpressionFunctionInvocationKey of(String functionName, Method method, Object[] arguments) {
        return new ExpressionFunctionInvocationKey(functionName, method, arguments);
    }

    private static Object snapshot(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null) {
            return NullValue.INSTANCE;
        }
        if (isKnownImmutable(value)) {
            return value;
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            return new IdentityValue(value);
        }
        try {
            if (value instanceof Map<?, ?> map) {
                Set<MapEntryValue> entries = new LinkedHashSet<>();
                map.forEach((key, item) -> entries.add(
                        new MapEntryValue(snapshot(key, visiting), snapshot(item, visiting))));
                return new MapValue(Collections.unmodifiableSet(entries));
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> items = new ArrayList<>();
                iterable.forEach(item -> items.add(snapshot(item, visiting)));
                return new IterableValue(List.copyOf(items));
            }
            if (value.getClass().isArray()) {
                List<Object> items = new ArrayList<>();
                for (int index = 0; index < Array.getLength(value); index++) {
                    items.add(snapshot(Array.get(value, index), visiting));
                }
                return new ArrayValue(List.copyOf(items));
            }
            return new IdentityValue(value);
        } finally {
            visiting.remove(value);
        }
    }

    private static boolean isKnownImmutable(Object value) {
        return value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal
                || value instanceof Enum<?>
                || value instanceof UUID
                || value instanceof TemporalAccessor;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExpressionFunctionInvocationKey that)) {
            return false;
        }
        return functionName.equals(that.functionName)
                && method.equals(that.method)
                && arguments.equals(that.arguments);
    }

    @Override
    public int hashCode() {
        int result = functionName.hashCode();
        result = 31 * result + method.hashCode();
        result = 31 * result + arguments.hashCode();
        return result;
    }

    private enum NullValue {
        INSTANCE
    }

    private record MapValue(Set<MapEntryValue> entries) {
    }

    private record MapEntryValue(Object key, Object value) {
    }

    private record IterableValue(List<Object> items) {
    }

    private record ArrayValue(List<Object> items) {
    }

    private static final class IdentityValue {
        private final Object value;

        private IdentityValue(Object value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityValue that && value == that.value;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }
    }
}
