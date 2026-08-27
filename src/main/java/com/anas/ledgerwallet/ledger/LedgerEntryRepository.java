package com.anas.ledgerwallet.ledger;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, UUID>, JpaSpecificationExecutor<LedgerEntry> {

    /**
     * The system-wide invariant, as one number: every entry ever written, summed.
     * Must always be exactly zero (prd.md, Invariant 2).
     */
    @Query("SELECT COALESCE(SUM(e.signedAmount), 0) FROM LedgerEntry e")
    BigDecimal sumAllSignedAmounts();

    /** What one account's balance should be, derived from its entries alone. */
    @Query("SELECT COALESCE(SUM(e.signedAmount), 0) FROM LedgerEntry e "
            + "WHERE e.account.id = :accountId")
    BigDecimal sumSignedAmountsForAccount(@Param("accountId") UUID accountId);
}
