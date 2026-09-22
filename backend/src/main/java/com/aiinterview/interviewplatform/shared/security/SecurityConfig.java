package com.aiinterview.interviewplatform.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   HandlerMappingIntrospector introspector)
            throws Exception {
        MvcRequestMatcher.Builder mvc = new MvcRequestMatcher.Builder(introspector);

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
                .anyRequest().authenticated())
            // Unauthenticated requests get 401, never a redirect to a login page.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }
}
