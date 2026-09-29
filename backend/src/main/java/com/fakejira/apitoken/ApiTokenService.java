package com.fakejira.apitoken;

import com.fakejira.user.AccountService.UserDeleting;
import com.fakejira.user.AccountStatus;
import com.fakejira.user.User;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class ApiTokenService {

    public static final String PREFIX = "fjt_";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiTokenRepository tokens;
    private final TransactionTemplate tx;

    public ApiTokenService(ApiTokenRepository tokens, TransactionTemplate tx) {
        this.tokens = tokens;
        this.tx = tx;
    }

    /** Who a presented token belongs to. */
    public record Identity(Long tokenId, Long userId, ApiToken.Scope scope) {
    }

    public record Created(ApiToken token, String secret) {
    }

    public Created create(User user, String name, ApiToken.Scope scope, Integer expiresInDays) {
        StringBuilder secret = new StringBuilder(PREFIX);
        for (int i = 0; i < 36; i++) {
            secret.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        String value = secret.toString();
        Instant expires = expiresInDays == null ? null : Instant.now().plus(Duration.ofDays(expiresInDays));
        ApiToken token = tokens.save(new ApiToken(user, name, value.substring(0, 10), hash(value), scope, expires));
        return new Created(token, value);
    }

    /** The token's owner if it is valid; also records when it was last used (at most once a minute). */
    public Optional<Identity> authenticate(String value) {
        if (value == null || !value.startsWith(PREFIX) || value.length() > 100) {
            return Optional.empty();
        }
        return Optional.ofNullable(tx.execute(status -> tokens.findByHash(hash(value))
                .filter(t -> !t.isExpired() && t.getUser().getStatus() == AccountStatus.ACTIVE)
                .map(t -> {
                    if (t.getLastUsedAt() == null || t.getLastUsedAt().isBefore(Instant.now().minusSeconds(60))) {
                        t.used();
                    }
                    return new Identity(t.getId(), t.getUser().getId(), t.getScope());
                })
                .orElse(null)));
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @EventListener
    public void onUserDeleting(UserDeleting event) {
        tokens.deleteForUser(event.userId());
    }
}
