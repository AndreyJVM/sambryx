package mari.samba.config;

import java.time.Clock;
import mari.samba.infra.ExecutionSessionManager;
import mari.samba.session.AdministratorSessionFilter;
import mari.samba.session.AdministratorSessions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Main Spring Security configuration class for Sambryx.
 * Configures authentication, authorization rules, CSRF protection, and static resource access.
 */
@Configuration
@EnableWebSecurity
@EnableScheduling
public class SecurityConfig {

    /**
     * An array of URL matchers defining paths that are publicly accessible without authentication.
     * Includes frontend assets, index pages, and the login/logout API endpoints.
     */
    private static final String[] PUBLIC_MATCHERS = {
        "/",
        "/ui/**",
        "/assets/**",
        "/index.html",
        "/favicon.ico",
        "/api/auth/login",
        "/api/auth/logout",
        "/api/auth/environment",
        "/error"
    };

    @Bean
    public Clock sessionClock() {
        return Clock.systemUTC();
    }

    @Bean
    public AdministratorSessions administratorSessions(ExecutionSessionManager execution, Clock clock) {
        return new AdministratorSessions(execution, clock);
    }

    /**
     * Configures the main security filter chain for HTTP requests.
     *
     * <p>Key configurations applied:
     * <ul>
     * <li>Allows public access to resources defined in {@code PUBLIC_MATCHERS}.</li>
     * <li>Requires authentication for all other requests.</li>
     * <li>Disables default form login and HTTP basic authentication in favor of a custom JSON API login.</li>
     * <li>Configures CSRF protection via a cookie-based repository, customized for Single Page Applications (SPAs).</li>
     * <li>Sets the default authentication entry point to return an HTTP 401 Unauthorized instead of redirecting to a login page.</li>
     * <li>Permits framing from the same origin.</li>
     * </ul>
     *
     * @param http the {@link HttpSecurity} to modify
     * @return the fully configured {@link SecurityFilterChain}
     * @throws Exception if an error occurs during configuration
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AdministratorSessions sessions) throws Exception {

        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        // Resolves CSRF token properly for SPAs (Angular/React/Vue)
        requestHandler.setCsrfRequestAttributeName(null);

        http.authorizeHttpRequests(authz -> authz.requestMatchers(PUBLIC_MATCHERS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(requestHandler))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/**")))
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin));

        http.addFilterAfter(new AdministratorSessionFilter(sessions), AuthorizationFilter.class);
        return http.build();
    }
}
