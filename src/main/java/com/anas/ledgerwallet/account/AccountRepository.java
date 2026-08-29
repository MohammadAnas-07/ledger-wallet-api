package com.anas.ledgerwallet.account;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** Scoped to one owner; ordered so the listing is stable between calls. */
    List<Account> findByOwnerIdOrderByCreatedAtAsc(UUID ownerId);

    boolean existsByAccountNumber(String accountNumber);

    /**
     * Loads an account under a row-level write lock (SELECT ... FOR UPDATE).
     *
     * <p>Used for the system account only. Optimistic locking is the right default
     * everywhere else: conflicts are rare between real users, and a lost race costs a
     * rollback rather than a wait. The system account is the exception because every
     * deposit and withdrawal posts its counter-entry against that one row, so writers
     * who are not competing for anything still collide on it — measured at 87% of
     * deposits failing with 409 under twelve concurrent depositors, each into a
     * different account of their own.
     *
     * <p>Waiting for the lock turns those collisions into short queues instead of
     * wasted work. The wait is unbounded (PostgreSQL's default), which is acceptable
     * because the transaction holding it is a handful of statements long.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
