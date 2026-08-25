package com.anas.ledgerwallet.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Expects an already lower-cased email; see {@code AuthService.normalise}. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
