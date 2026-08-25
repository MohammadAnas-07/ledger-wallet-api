package com.anas.ledgerwallet.common.security;

import com.anas.ledgerwallet.auth.User;
import com.anas.ledgerwallet.auth.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the bearer token on each request and, if it is valid, populates the
 * {@code SecurityContext}.
 *
 * <p>This filter never rejects a request itself. An absent or invalid token simply
 * leaves the context empty, and the filter chain's own rules decide what that means —
 * so a public endpoint keeps working with a junk token, and a protected one returns
 * 401 through the single entry point rather than from here.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        extractBearerToken(request)
                .flatMap(jwtService::extractUserId)
                // The user is re-read rather than trusted from the token claims: a
                // deleted account must stop working immediately, not when its last
                // issued token happens to expire.
                .flatMap(userRepository::findById)
                .ifPresent(user -> authenticate(user, request));

        filterChain.doFilter(request, response);
    }

    private Optional<String> extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private void authenticate(User user, HttpServletRequest request) {
        AuthenticatedUser principal =
                new AuthenticatedUser(user.getId(), user.getEmail(), user.getPasswordHash());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
