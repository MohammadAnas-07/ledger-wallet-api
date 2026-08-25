package com.anas.ledgerwallet.auth;

import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import com.anas.ledgerwallet.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration, login, and the caller's own profile.
 *
 * <p>HTTP concerns only: mapping, validation, status codes. The rules live in
 * {@code AuthService} (rules.md 4.4).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Protected, unlike register and login — those two are the only public paths.
     *
     * <p>The identity comes from the {@code SecurityContext}, never from a path
     * variable or the request body, so there is no id for a caller to swap for
     * someone else's (rules.md 2.1).
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser caller) {
        return ResponseEntity.ok(authService.currentUser(caller.getId()));
    }
}
