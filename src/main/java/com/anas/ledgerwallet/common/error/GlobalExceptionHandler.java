package com.anas.ledgerwallet.common.error;

import com.anas.ledgerwallet.account.AccountNotFoundException;
import com.anas.ledgerwallet.auth.EmailAlreadyRegisteredException;
import com.anas.ledgerwallet.ledger.IdempotencyKeyReuseException;
import com.anas.ledgerwallet.ledger.InsufficientFundsException;
import com.anas.ledgerwallet.ledger.SelfTransferException;
import com.anas.ledgerwallet.ledger.TransactionNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
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

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFound(
            TransactionNotFoundException e, HttpServletRequest request) {

        return build(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(
            AccountNotFoundException e, HttpServletRequest request) {

        return build(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", e.getMessage(), request);
    }

    /**
     * The caller is authenticated but the resource is not theirs.
     *
     * <p>The message is deliberately generic â€” it must not describe whose the resource
     * is or what it holds.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException e, HttpServletRequest request) {

        return build(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have access to this resource", request);
    }

    /**
     * The request was well-formed and authorised, but a business rule refused it.
     *
     * <p>422 rather than 400: nothing about the payload is wrong, so a client cannot
     * fix it by correcting the request. No ledger entry was written.
     */
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(
            InsufficientFundsException e, HttpServletRequest request) {

        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                e.getMessage(), request);
    }

    /**
     * A transfer named the same account on both sides.
     *
     * <p>400 rather than 422: the payload itself is contradictory, and the caller can
     * fix it by naming a different destination.
     */
    @ExceptionHandler(SelfTransferException.class)
    public ResponseEntity<ErrorResponse> handleSelfTransfer(
            SelfTransferException e, HttpServletRequest request) {

        return build(HttpStatus.BAD_REQUEST, "SELF_TRANSFER_NOT_ALLOWED",
                e.getMessage(), request);
    }

    /**
     * Two writers raced for the same account and this one lost.
     *
     * <p>Not an error in the code: it is the locking doing its job. The whole
     * transaction rolled back, nothing partial was written, and the caller may retry â€”
     * safely, if they sent an idempotency key (architecture.md 3).
     *
     * <p>Catches {@link ConcurrencyFailureException} rather than only the optimistic
     * subclass, so a database-level deadlock or lock timeout
     * ({@code CannotAcquireLockException}) is also reported as a retryable conflict.
     * Handling only the optimistic case left the pessimistic one falling through to
     * the catch-all below and surfacing as a 500 â€” a contention failure dressed up as
     * a server fault, which tells the caller to give up when they should retry.
     */
    /**
     * The caller reused one of their own idempotency keys for a different request.
     *
     * <p>409 like the lock conflict below, but not retryable in the same way: retrying
     * the same request will keep failing. The caller has to send a new key, or repeat
     * the original request unchanged to get its result back.
     */
    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyKeyReuse(
            IdempotencyKeyReuseException e, HttpServletRequest request) {

        return build(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", e.getMessage(), request);
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrencyFailure(
            ConcurrencyFailureException e, HttpServletRequest request) {

        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The account was modified concurrently; please retry", request);
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
