package com.anas.ledgerwallet.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import com.anas.ledgerwallet.common.security.AuthenticatedUser;
import com.anas.ledgerwallet.common.security.JwtService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;

    @InjectMocks private AuthService authService;

    private static RegisterRequest registration(String email) {
        return new RegisterRequest(email, "a-sufficiently-long-password", "Test User");
    }

    @Test
    @DisplayName("Registration stores a hash, never the raw password")
    void hashesPassword() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode("a-sufficiently-long-password")).thenReturn("hashed-value");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        authService.register(registration("user@example.com"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed-value");
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("a-sufficiently-long-password");
    }

    @Test
    @DisplayName("Registration lower-cases the email before storing it")
    void normalisesEmail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-value");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        authService.register(registration("  User@Example.COM  "));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        // Without this, User@Example.com and user@example.com become two accounts and
        // the unique index never objects.
        assertThat(saved.getValue().getEmail()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("A duplicate email is rejected and nothing is written")
    void rejectsDuplicateEmail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registration("user@example.com")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("A unique-index collision is reported as a duplicate, not a 500")
    void translatesUniqueViolation() {
        // Two concurrent registrations can both pass the existsByEmail check; only the
        // database catches the second one.
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-value");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("ux_users_email"));

        assertThatThrownBy(() -> authService.register(registration("user@example.com")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    @DisplayName("The registration response carries no credential material")
    void responseExcludesHash() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-value");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse response = authService.register(registration("user@example.com"));

        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.toString()).doesNotContain("hashed-value");
    }

    @Test
    @DisplayName("Login issues a token for the authenticated user")
    void loginIssuesToken() {
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal =
                new AuthenticatedUser(userId, "user@example.com", "hashed-value");
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(principal, null));
        when(jwtService.generateToken(userId, "user@example.com")).thenReturn("a-token");
        when(jwtService.getTokenLifetimeSeconds()).thenReturn(900L);

        AuthResponse response =
                authService.login(new LoginRequest("user@example.com", "a-password"));

        assertThat(response.accessToken()).isEqualTo("a-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresInSeconds()).isEqualTo(900L);
    }

    @Test
    @DisplayName("Login normalises the email before authenticating")
    void loginNormalisesEmail() {
        AuthenticatedUser principal =
                new AuthenticatedUser(UUID.randomUUID(), "user@example.com", "hashed-value");
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(principal, null));
        when(jwtService.generateToken(any(), anyString())).thenReturn("a-token");
        when(jwtService.getTokenLifetimeSeconds()).thenReturn(900L);

        authService.login(new LoginRequest("USER@Example.com", "a-password"));

        ArgumentCaptor<UsernamePasswordAuthenticationToken> attempt =
                ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);
        verify(authenticationManager).authenticate(attempt.capture());
        // Otherwise a user who registered lower-cased could not log in with the
        // capitalisation their mail client shows them.
        assertThat(attempt.getValue().getPrincipal()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("Bad credentials propagate and issue no token")
    void loginRejectsBadCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() ->
                authService.login(new LoginRequest("user@example.com", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class);

        verify(jwtService, never()).generateToken(any(), anyString());
    }

    @Test
    @DisplayName("The caller's profile is looked up by the id from the security context")
    void currentUserReadsById() {
        UUID userId = UUID.randomUUID();
        User user = new User("user@example.com", "hashed-value", "Test User", Instant.now());
        when(userRepository.findById(eq(userId))).thenReturn(java.util.Optional.of(user));

        UserResponse response = authService.currentUser(userId);

        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.fullName()).isEqualTo("Test User");
    }
}
