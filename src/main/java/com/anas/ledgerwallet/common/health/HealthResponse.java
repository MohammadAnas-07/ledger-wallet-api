package com.anas.ledgerwallet.common.health;

/**
 * Response body for the health endpoint.
 *
 * <p>A record, not the entity/DTO distinction of later phases — there is no domain
 * object behind this. It exists so the endpoint returns a stable JSON shape rather
 * than a bare string.
 */
public record HealthResponse(String status) {

    public static HealthResponse up() {
        return new HealthResponse("UP");
    }
}
