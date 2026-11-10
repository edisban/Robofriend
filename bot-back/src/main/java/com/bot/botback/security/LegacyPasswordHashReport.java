package com.bot.botback.security;

import com.bot.botback.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs at startup how many accounts still have an old BCrypt password hash. They're upgraded to
 * Argon2id when those users next sign in; at zero, BCrypt can be removed from PasswordEncoderConfig.
 */
@Component
public class LegacyPasswordHashReport {

    private static final Logger log = LoggerFactory.getLogger(LegacyPasswordHashReport.class);

    private final UserRepository userRepository;

    public LegacyPasswordHashReport(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        try {
            long legacy = userRepository.countByPasswordHashNotLike("{argon2}%");
            if (legacy > 0) {
                log.info("{} account(s) still have a BCrypt password hash; each is upgraded to Argon2id at its next sign-in",
                        legacy);
            } else {
                log.info("All password hashes are Argon2id; BCrypt is no longer needed");
            }
        } catch (Exception e) {
            log.warn("Could not count legacy password hashes: {}", e.getMessage());
        }
    }
}
