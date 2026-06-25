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
package app.epistola.valtimo.service;

import java.util.Collections;
import java.util.Map;

/**
 * Exception thrown when an Epistola API call fails.
 *
 * <p>When the failure originates from a downstream HTTP error, the exception can
 * additionally carry the downstream HTTP status, the RFC-9457 problem {@code type}
 * URI, and the structured problem extension members (e.g. {@code version} /
 * {@code baselineVersion} for a {@code catalog-schema-too-old} problem). Callers
 * use these to map the failure to a correct status class and to translate the
 * remediation for their audience instead of surfacing a bare status code.
 */
public class EpistolaApiException extends RuntimeException {

    private final Integer httpStatus;
    private final String problemType;
    private final transient Map<String, Object> problemExtensions;

    public EpistolaApiException(String message) {
        this(message, null, null, null, null);
    }

    public EpistolaApiException(String message, Throwable cause) {
        this(message, cause, null, null, null);
    }

    public EpistolaApiException(String message, Throwable cause, Integer httpStatus,
                               String problemType, Map<String, Object> problemExtensions) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.problemType = problemType;
        this.problemExtensions = problemExtensions == null
                ? Map.of()
                : Collections.unmodifiableMap(problemExtensions);
    }

    /** Downstream HTTP status code, or {@code null} when the failure was not an HTTP response. */
    public Integer getHttpStatus() {
        return httpStatus;
    }

    /** RFC-9457 problem {@code type} URI, or {@code null} when none was present. */
    public String getProblemType() {
        return problemType;
    }

    /** The trailing slug of {@link #getProblemType()} (e.g. {@code catalog-schema-too-old}), or {@code null}. */
    public String getProblemTypeSlug() {
        if (problemType == null || problemType.isBlank()) {
            return null;
        }
        int slash = problemType.lastIndexOf('/');
        return slash >= 0 && slash < problemType.length() - 1
                ? problemType.substring(slash + 1)
                : problemType;
    }

    /** Structured RFC-9457 extension members (never null; empty when none). */
    public Map<String, Object> getProblemExtensions() {
        return problemExtensions;
    }

    /** {@code true} when the downstream failure was a client-class (4xx) response. */
    public boolean isClientError() {
        return httpStatus != null && httpStatus >= 400 && httpStatus < 500;
    }
}
