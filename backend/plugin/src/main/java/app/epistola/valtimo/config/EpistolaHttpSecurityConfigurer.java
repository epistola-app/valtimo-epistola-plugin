package app.epistola.valtimo.config;

import com.ritense.valtimo.contract.security.config.HttpConfigurerConfigurationException;
import com.ritense.valtimo.contract.security.config.HttpSecurityConfigurer;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Security configuration for Epistola plugin REST endpoints.
 * Order 270 ensures this runs before Valtimo's default anyRequest() configuration.
 * <p>
 * Preview and document download are available to authenticated users. Admin and
 * tooling endpoints require ROLE_ADMIN.
 */
@Order(270)
public class EpistolaHttpSecurityConfigurer implements HttpSecurityConfigurer {

    @Override
    public void configure(HttpSecurity http) {
        try {
            http.authorizeHttpRequests(requests -> requests
                    // Preview and download are used in user task forms — any authenticated user
                    .requestMatchers("/api/v1/plugin/epistola/preview").authenticated()
                    .requestMatchers("/api/v1/plugin/epistola/preview-sources").authenticated()
                    .requestMatchers("/api/v1/plugin/epistola/documents/*/download").authenticated()
                    // All other Epistola endpoints require ROLE_ADMIN
                    .requestMatchers("/api/v1/plugin/epistola/**").hasAuthority("ROLE_ADMIN")
            );
        } catch (Exception e) {
            throw new HttpConfigurerConfigurationException(e);
        }
    }
}
