// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.documents

import com.ritense.valtimo.contract.security.config.HttpConfigurerConfigurationException
import com.ritense.valtimo.contract.security.config.HttpSecurityConfigurer
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity

/**
 * Lets an authenticated user download a stored letter.
 *
 * Valtimo's own resource security permits `/api/v1/resource`,
 * `/api/v1/resource/{resourceId}` and `/api/v1/resource/pre-signed-url/{fileName}` — but **not**
 * `/api/v1/resource/{resourceId}/download`, which is the path its own case UI calls. In a stock
 * installation that path is therefore unimplemented (204) *and* unauthorized (403), which fits the
 * local resource module being a placeholder. Measured: the first real download returned 403 with
 * the file sitting in the table.
 *
 * **Authenticated, not case-scoped.** Anyone signed in who knows a resource id can fetch that file.
 * That matches how Valtimo treats its own resource endpoints — the id is the capability — and it is
 * a demo-grade decision: a real installation serves files from the Documenten API, which applies
 * its own authorization. Worth knowing before copying this into one.
 */
@Order(275)
class DemoResourceSecurityConfigurer : HttpSecurityConfigurer {
    override fun configure(http: HttpSecurity) {
        try {
            http.authorizeHttpRequests { requests ->
                requests.requestMatchers("/api/v1/resource/{resourceId}/download").authenticated()
            }
        } catch (e: Exception) {
            throw HttpConfigurerConfigurationException(e)
        }
    }
}