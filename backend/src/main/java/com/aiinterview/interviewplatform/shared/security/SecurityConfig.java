package com.aiinterview.interviewplatform.shared.security;

import com.aiinterview.interviewplatform.shared.config.AppProperties;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;
import org.springframework.security.web.servlet.util.matcher.MvcRequestMatcher;

/**
 * Security foundation for M0/M1.
 *
 * <p>Deliberately contains <strong>no authentication mechanism</strong>. JWKS
 * verification of Supabase tokens, just-in-time user provisioning and role
 * resolution belong to M6. What exists here is the shape those will plug
 * into, plus a safe default: everything that is not explicitly public
 * requires authentication, so a new endpoint is closed until someone opens it.
 *
 * <p>Sessions are stateless and CSRF protection is disabled because the API
 * uses no cookies. If a cookie is ever introduced, CSRF tokens become
 * mandatory in the same change (docs/08-security.md §10).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Environments in which the unauthenticated candidate API is reachable.
     *
     * <p>M5A exposes the interview flow with no authentication so the product
     * can be exercised end to end. That is acceptable on a developer's machine
     * and in tests, and nowhere else — so rather than trusting a reviewer to
     * notice, the rule is expressed as data and enforced below. In staging or
     * production the same endpoints exist but require a credential nobody can
     * present yet, so they answer 401 until M6 makes them real.
     */
    private static final Set<String> CANDIDATE_API_ENVIRONMENTS = Set.of("local", "test");

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   HandlerMappingIntrospector introspector,
                                                   AppProperties properties)
            throws Exception {
        MvcRequestMatcher.Builder mvc = new MvcRequestMatcher.Builder(introspector);
        boolean candidateApiOpen =
                CANDIDATE_API_ENVIRONMENTS.contains(properties.environment());

        if (candidateApiOpen) {
            log.warn("Candidate API is UNAUTHENTICATED in environment '{}'. "
                            + "This is the M5A development slice; authentication arrives in M6.",
                    properties.environment());
        }

        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(Customizer.withDefaults()))
            .authorizeHttpRequests(auth -> auth
                // Liveness/readiness must answer without credentials.
                .requestMatchers(mvc.pattern("/actuator/health"),
                                 mvc.pattern("/actuator/health/**"))
                    .permitAll()
                // Admin surface is separated by path so the rule is auditable
                // at a glance. The role check itself arrives with M6/M9.
                .requestMatchers(mvc.pattern("/api/v1/admin/**")).authenticated()
                // The M5A candidate slice, open only where it is safe to be.
                // Listed before the catch-all; outside local/test the branch is
                // skipped entirely and these paths fall through to authenticated().
                .requestMatchers(candidateApiOpen
                        ? new MvcRequestMatcher[]{
                            mvc.pattern("/api/v1/interviews/**"),
                            mvc.pattern("/api/v1/interview-templates/**")}
                        : new MvcRequestMatcher[]{mvc.pattern("/__never_matches__")})
                    .permitAll()
                .anyRequest().authenticated())
            // Unauthenticated requests get 401, never a redirect to a login page.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    /**
     * CORS for the Vite dev server, and only for it.
     *
     * <p>Origins are listed exactly rather than wildcarded: the frontend runs on
     * a different port in development, which is a genuine cross-origin call, but
     * a permissive {@code *} would follow this configuration into an environment
     * where it matters. Outside local/test the list is empty, so no cross-origin
     * request is allowed at all.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration config = new CorsConfiguration();

        if (CANDIDATE_API_ENVIRONMENTS.contains(properties.environment())) {
            config.setAllowedOrigins(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
            config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
            config.setAllowedHeaders(List.of("Content-Type", "X-Trace-Id"));
            // So the browser can read the id and a user can quote it to us.
            config.setExposedHeaders(List.of("X-Trace-Id"));
            config.setMaxAge(3600L);
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
