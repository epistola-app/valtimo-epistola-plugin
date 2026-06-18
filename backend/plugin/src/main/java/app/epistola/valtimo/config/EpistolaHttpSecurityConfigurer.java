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
package app.epistola.valtimo.config;

import com.ritense.valtimo.contract.security.config.HttpConfigurerConfigurationException;
import com.ritense.valtimo.contract.security.config.HttpSecurityConfigurer;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Security configuration for Epistola plugin REST endpoints.
 *
 * <p>Order 270 ensures this runs before Valtimo's default {@code anyRequest()} configuration.
 *
 * <p>Two layers of authorization apply:
 * <ul>
 *   <li><b>HTTP layer (this configurer)</b> — coarse-grained role gates. Everything
 *       authenticated by default; configurator-only endpoints additionally require
 *       {@code ROLE_ADMIN} (the de-facto "process-link author" authority in Valtimo 13.21,
 *       since process-link CRUD itself requires ROLE_ADMIN).</li>
 *   <li><b>Controller layer (PBAC)</b> — fine-grained checks via {@code AuthorizationService}.
 *       Preview / retry-form bind the request to the supplied taskId's process and case
 *       document and require {@code OperatonTask:VIEW}; document download requires
 *       {@code OperatonTask:VIEW} on the supplied taskId. Admin endpoints check
 *       {@code EpistolaAdministration:MANAGE}.</li>
 * </ul>
 */
@Order(270)
public class EpistolaHttpSecurityConfigurer implements HttpSecurityConfigurer {

    @Override
    public void configure(HttpSecurity http) {
        try {
            http.authorizeHttpRequests(requests -> requests
                    // Configurator endpoints (process-link configuration UI) — ROLE_ADMIN at HTTP layer.
                    // Anyone who can author process links in Valtimo today already has ROLE_ADMIN.
                    .requestMatchers("/api/v1/plugin/epistola/configurations/**").hasAuthority("ROLE_ADMIN")
                    .requestMatchers("/api/v1/plugin/epistola/process-variables").hasAuthority("ROLE_ADMIN")
                    .requestMatchers("/api/v1/plugin/epistola/variable-suggestions").hasAuthority("ROLE_ADMIN")
                    .requestMatchers("/api/v1/plugin/epistola/expression-functions").hasAuthority("ROLE_ADMIN")
                    .requestMatchers("/api/v1/plugin/epistola/validate-jsonata").hasAuthority("ROLE_ADMIN")
                    .requestMatchers("/api/v1/plugin/epistola/evaluate-mapping").hasAuthority("ROLE_ADMIN")
                    // All other Epistola endpoints (admin, preview, download, retry-form)
                    // authenticate at the HTTP layer; the controllers enforce PBAC.
                    .requestMatchers("/api/v1/plugin/epistola/**").authenticated()
            );
        } catch (Exception e) {
            throw new HttpConfigurerConfigurationException(e);
        }
    }
}
