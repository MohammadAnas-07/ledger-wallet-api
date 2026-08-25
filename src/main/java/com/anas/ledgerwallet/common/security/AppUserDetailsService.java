package com.anas.ledgerwallet.common.security;

import com.anas.ledgerwallet.auth.User;
import com.anas.ledgerwallet.auth.UserRepository;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads users for authentication.
 *
 * <p>Used by {@code DaoAuthenticationProvider} during login. When no user matches, the
 * provider still runs a dummy password comparison before failing, so an unknown email
 * and a wrong password take roughly the same time — without that, response timing
 * alone would reveal which emails are registered.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("No user for the given email"));

        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getPasswordHash());
    }
}
