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
 *       Preview / preview-sources / download / retry-form check
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
                    // All other Epistola endpoints (admin, preview, preview-sources, download,
                    // retry-form) authenticate at the HTTP layer; the controllers enforce PBAC.
                    .requestMatchers("/api/v1/plugin/epistola/**").authenticated()
            );
        } catch (Exception e) {
            throw new HttpConfigurerConfigurationException(e);
        }
    }
}
