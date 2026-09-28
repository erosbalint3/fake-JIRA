package com.fakejira.auth;

import com.fakejira.common.ApiException;
import com.fakejira.mail.MailService;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    static final Duration VALIDITY = Duration.ofHours(1);

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final MailService mail;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                                PasswordEncoder passwordEncoder, MailService mail) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.mail = mail;
    }

    /**
     * Emails a reset link if the address belongs to an account. Always succeeds so the
     * response does not reveal which emails are registered.
     */
    @Transactional
    public void requestReset(String email) {
        users.findByEmailIgnoreCase(email.trim()).ifPresent(user -> {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            tokens.deleteForUser(user.getId());
            tokens.save(new PasswordResetToken(user, hash(token), Instant.now().plus(VALIDITY)));
            String link = mail.link("/reset-password?token=" + token);
            if (mail.isEnabled()) {
                mail.send(user.getEmail(), "[FakeJIRA] Reset your password",
                        "Hi " + user.getUsername() + ",\n\nUse this link to choose a new password (valid for 1 hour):\n"
                                + link + "\n\nIf you did not ask for this, you can ignore this email.");
            } else {
                // Without SMTP the administrator can hand the link over manually.
                log.warn("Email is not configured. Password reset link for {}: {}", user.getUsername(), link);
            }
        });
    }

    @Transactional
    public void reset(String token, String newPassword) {
        PasswordResetToken reset = tokens.findByTokenHash(hash(token))
                .filter(candidate -> candidate.isUsable(Instant.now()))
                .orElseThrow(() -> ApiException.badRequest("This reset link is invalid or has expired."));
        User user = reset.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        reset.markUsed();
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
