package com.anas.ledgerwallet.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Loads a transaction together with both of its entries.
     *
     * <p>Fetched in one query rather than lazily: the detail view always needs both
     * sides, so leaving them lazy would mean a second round trip for data we know is
     * required.
     */
    @Query("SELECT t FROM Transaction t LEFT JOIN FETCH t.entries WHERE t.id = :id")
    Optional<Transaction> findByIdWithEntries(@Param("id") UUID id);
}
