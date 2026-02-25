package app.epistola.valtimo.config;

import com.ritense.valtimo.contract.security.config.HttpConfigurerConfigurationException;
import com.ritense.valtimo.contract.security.config.HttpSecurityConfigurer;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Security configuration for Epistola plugin REST endpoints.
 * Order 270 ensures this runs before Valtimo's default anyRequest() configuration.
 * <p>
 * The callback endpoint is accessible without authentication (for webhooks from Epistola).
 * All other endpoints require authentication.
 */
@Order(270)
public class EpistolaHttpSecurityConfigurer implements HttpSecurityConfigurer {

    @Override
    public void configure(HttpSecurity http) {
        try {
            http.authorizeHttpRequests(requests -> requests
                    // Callback endpoint is public (webhook from Epistola)
                    .requestMatchers("/api/v1/plugin/epistola/callback/**").permitAll()
                    // All other Epistola endpoints require authentication
                    .requestMatchers("/api/v1/plugin/epistola/**").authenticated()
            );
        } catch (Exception e) {
            throw new HttpConfigurerConfigurationException(e);
        }
    }
}
