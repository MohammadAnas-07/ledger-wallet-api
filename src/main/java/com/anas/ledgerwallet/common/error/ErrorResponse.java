package com.anas.ledgerwallet.common.error;

import java.time.Instant;

/** The single error shape for every failed request (architecture.md 7). */
public record ErrorResponse(String code, String message, Instant timestamp, String path) {

    public static ErrorResponse of(String code, String message, String path) {
        return new ErrorResponse(code, message, Instant.now(), path);
    }
}
