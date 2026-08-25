package com.anas.ledgerwallet.common.error;

import com.anas.ledgerwallet.account.AccountNotFoundException;
import com.anas.ledgerwallet.auth.EmailAlreadyRegisteredException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into the standard error body.
 *
 * <p>No stack trace, SQL, or internal class name reaches a response: those go to the
 * log, and the caller gets a code and a message safe to show (rules.md 4.5).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException e, HttpServletRequest request) {

        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining("; "));

        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEmail(
            EmailAlreadyRegisteredException e, HttpServletRequest request) {

        return build(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", e.getMessage(), request);
    }

    /**
     * One message for every credential failure.
     *
     * <p>Never distinguishes an unknown email from a wrong password: that difference
     * tells an attacker which emails are registered (rules.md 2.2).
     */
    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ErrorResponse> handleBadCredentials(
            AuthenticationException e, HttpServletRequest request) {

        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Invalid email or password", request);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(
            AccountNotFoundException e, HttpServletRequest request) {

        return build(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", e.getMessage(), request);
    }

    /**
     * The caller is authenticated but the resource is not theirs.
     *
     * <p>The message is deliberately generic — it must not describe whose the resource
     * is or what it holds.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException e, HttpServletRequest request) {

        return build(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have access to this resource", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception e, HttpServletRequest request) {

        // Logged in full here precisely so it does not have to travel in the response.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), e);

        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request);
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status, String code, String message, HttpServletRequest request) {

        return ResponseEntity.status(status)
                .body(ErrorResponse.of(code, message, request.getRequestURI()));
    }
}
