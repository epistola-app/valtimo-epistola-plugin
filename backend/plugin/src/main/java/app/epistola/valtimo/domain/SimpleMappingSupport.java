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
package app.epistola.valtimo.domain;

/**
 * Describes how completely a template schema can be represented by the Simple data mapper.
 */
public record SimpleMappingSupport(
        Level level,
        String reason
) {
    public enum Level {
        FULL,
        PARTIAL,
        UNSUPPORTED
    }

    public static SimpleMappingSupport full() {
        return new SimpleMappingSupport(Level.FULL, null);
    }

    public static SimpleMappingSupport partial(String reason) {
        return new SimpleMappingSupport(Level.PARTIAL, reason);
    }

    public static SimpleMappingSupport unsupported(String reason) {
        return new SimpleMappingSupport(Level.UNSUPPORTED, reason);
    }
}
