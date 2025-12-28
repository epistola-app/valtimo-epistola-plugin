package app.epistola.valtimo.config;

import com.ritense.valtimo.contract.security.config.HttpConfigurerConfigurationException;
import com.ritense.valtimo.contract.security.config.HttpSecurityConfigurer;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Security configuration for Epistola plugin REST endpoints.
 * Order 270 ensures this runs before Valtimo's default anyRequest() configuration.
 */
@Order(270)
public class EpistolaHttpSecurityConfigurer implements HttpSecurityConfigurer {

    @Override
    public void configure(HttpSecurity http) {
        try {
            http.authorizeHttpRequests(requests ->
                requests.requestMatchers("/api/v1/plugin/epistola/**").authenticated()
            );
        } catch (Exception e) {
            throw new HttpConfigurerConfigurationException(e);
        }
    }
}
