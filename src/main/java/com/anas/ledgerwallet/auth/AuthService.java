package com.anas.ledgerwallet.auth;

import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import com.anas.ledgerwallet.common.security.AuthenticatedUser;
import com.anas.ledgerwallet.common.security.JwtService;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration and login. */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalise(request.email());

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = new User(
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                Instant.now());

        try {
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // The check above is not enough on its own: two simultaneous registrations
            // for the same email can both pass it and only collide at the unique
            // index. Translating here turns that race into the same 409 the caller
            // would have got sequentially, rather than a 500.
            throw new EmailAlreadyRegisteredException();
        }
    }

    public AuthResponse login(LoginRequest request) {
        // Delegating to the AuthenticationManager rather than comparing hashes here:
        // it fails identically for an unknown email and a wrong password, including
        // running a dummy hash comparison for unknown emails so the two cases cannot
        // be told apart by response timing. A BadCredentialsException from here is
        // translated to a single generic 401 (rules.md 2.2).
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        normalise(request.email()), request.password()));

        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        String token = jwtService.generateToken(principal.getId(), principal.getEmail());

        return AuthResponse.bearer(token, jwtService.getTokenLifetimeSeconds());
    }

    /**
     * The caller's own profile.
     *
     * <p>The user is known to exist: {@code JwtAuthenticationFilter} re-reads it from
     * the database before authenticating the request, so a missing row here would mean
     * the account was deleted mid-request.
     */
    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user no longer exists: " + userId));
    }

    /**
     * Email is case-insensitive in practice. Without this, "A@example.com" and
     * "a@example.com" would be two accounts and the unique index would not object.
     */
    private String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
