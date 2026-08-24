package com.anas.ledgerwallet.common.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint.
 *
 * <p>Reports only that the application is up and serving requests. It deliberately
 * does not check the database or any downstream dependency, and it exposes no
 * application state — it is one of the two publicly reachable paths in
 * {@code SecurityConfig}, so anything it returns is returned to unauthenticated
 * callers.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(HealthResponse.up());
    }
}
