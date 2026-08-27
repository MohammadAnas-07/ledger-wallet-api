package com.anas.ledgerwallet.ledger;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Query predicates for the statement.
 *
 * <p>Built with the Criteria API rather than by assembling query strings. Nothing here
 * concatenates a caller-supplied value into JPQL, so a date range cannot become an
 * injection point however odd its contents (rules.md 2.4).
 */
final class LedgerEntrySpecifications {

    private LedgerEntrySpecifications() {
    }

    static Specification<LedgerEntry> forAccount(UUID accountId) {
        return (root, query, builder) ->
                builder.equal(root.get("account").get("id"), accountId);
    }

    /** Inclusive lower bound; ignored when null. */
    static Specification<LedgerEntry> createdAtOrAfter(Instant from) {
        return (root, query, builder) -> from == null
                ? builder.conjunction()
                : builder.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    /** Inclusive upper bound; ignored when null. */
    static Specification<LedgerEntry> createdAtOrBefore(Instant to) {
        return (root, query, builder) -> to == null
                ? builder.conjunction()
                : builder.lessThanOrEqualTo(root.get("createdAt"), to);
    }
}
