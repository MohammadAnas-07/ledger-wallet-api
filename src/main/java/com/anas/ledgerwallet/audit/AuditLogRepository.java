package com.anas.ledgerwallet.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    boolean existsByEventId(UUID eventId);

    List<AuditLogEntry> findByTransactionId(UUID transactionId);
}
