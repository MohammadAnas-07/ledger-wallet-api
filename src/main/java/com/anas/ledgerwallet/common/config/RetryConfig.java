package com.anas.ledgerwallet.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Turns on {@code @Retryable} processing.
 *
 * <p>Without this the annotation is inert — the method runs once and the retry never
 * happens, silently.
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
