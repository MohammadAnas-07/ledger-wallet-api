package com.anas.ledgerwallet.common.config;

import com.anas.ledgerwallet.common.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security filter chain.
 *
 * <p>The chain is stateless and denies by default: any endpoint added from here on is
 * protected unless it is explicitly listed as public below. Making a path public is
 * therefore a visible, reviewable change rather than an omission (rules.md 2.1).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The only publicly reachable paths. Additions here need a deliberate decision.
     *
     * <p>Listed individually rather than as {@code /api/v1/auth/**}: a wildcard would
     * silently make every future endpoint under that prefix public too, and
     * {@code /api/v1/auth/me} is exactly such an endpoint — it must stay protected.
     */
    private static final String[] PUBLIC_PATHS = {
        "/health",
        "/api/v1/auth/register",
        "/api/v1/auth/login"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

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
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Return a bare 401 instead of redirecting to a login page or
                // prompting for basic auth: this is an API, not a browser app.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /**
     * BCrypt at strength 12 (rules.md 2.2).
     *
     * <p>Higher than the Spring default of 10: each increment doubles the work, which
     * is negligible on a login request and expensive at scale for anyone brute-forcing
     * a leaked table of hashes.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Default, restated because it is load-bearing: an unknown email surfaces as
        // BadCredentialsException rather than UsernameNotFoundException, so the two
        // failures are indistinguishable to the caller.
        provider.setHideUserNotFoundExceptions(true);

        return new ProviderManager(provider);
    }
}
