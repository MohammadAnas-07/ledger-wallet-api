package com.anas.ledgerwallet.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Security filter chain.
 *
 * <p>The chain is stateless and denies by default: any endpoint added from here on is
 * protected unless it is explicitly listed as public below. Making a path public is
 * therefore a visible, reviewable change rather than an omission (rules.md 2.1).
 *
 * <p>Authentication itself arrives in Phase 2 (JWT). Until then every path except
 * {@code /health} returns 401 — which is the correct behaviour, not a gap.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The only publicly reachable paths. Additions here need a deliberate decision. */
    private static final String[] PUBLIC_PATHS = {"/health"};

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // No cookies or sessions are used, so there is no CSRF vector to defend.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                // Return a bare 401 instead of redirecting to a login page or
                // prompting for basic auth: this is an API, not a browser app.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }
}
