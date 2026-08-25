package com.anas.ledgerwallet.account;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** Scoped to one owner; ordered so the listing is stable between calls. */
    List<Account> findByOwnerIdOrderByCreatedAtAsc(UUID ownerId);

    boolean existsByAccountNumber(String accountNumber);
}
