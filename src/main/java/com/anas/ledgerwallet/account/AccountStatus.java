package com.anas.ledgerwallet.account;

/**
 * Lifecycle state of an account.
 *
 * <p>Only {@link #ACTIVE} is reachable in Phase 3 — nothing sets the other two yet.
 * They are declared because money movement from Phase 4 has to refuse a frozen or
 * closed account, and a status column added later would need a migration over rows
 * that already exist.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
