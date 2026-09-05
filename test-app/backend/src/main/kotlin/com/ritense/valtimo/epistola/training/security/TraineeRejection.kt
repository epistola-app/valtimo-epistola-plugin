// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

package com.ritense.valtimo.epistola.training.security

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType

/**
 * Writes a 403 with an actual, readable body — used by [TraineeAdminSurfaceGuardFilter] and
 * [TraineeOwnershipInterceptor] instead of the plain `HttpServletResponse.sendError(int, String)`
 * both used to call.
 *
 * `sendError`'s message parameter looks like it reaches the client, but it doesn't: Spring Boot's
 * default error-page rendering (`server.error.include-message` defaults to `never`, precisely to
 * avoid leaking exception details in production) strips it, leaving an empty body — confirmed by
 * actually inspecting the response over real HTTP (`Content-Length: 0`, a bare `WWW-Authenticate`
 * challenge header from the OAuth2 resource-server's own 403 handling, no trace of the reason
 * string), not by reading `sendError`'s Javadoc. Writing the body directly here sidesteps that
 * error-page dispatch entirely, so the reason a trainee gets rejected is always visible to them,
 * regardless of that app-wide setting.
 */
internal fun HttpServletResponse.rejectAsForbidden(reason: String) {
    status = HttpServletResponse.SC_FORBIDDEN
    contentType = MediaType.APPLICATION_JSON_VALUE
    characterEncoding = "UTF-8"
    writer.write("""{"status":403,"message":${jsonString(reason)}}""")
}

private fun jsonString(value: String): String {
    val escaped =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    return "\"$escaped\""
}