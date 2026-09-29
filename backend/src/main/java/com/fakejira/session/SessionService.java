package com.fakejira.session;

import com.fakejira.config.JwtProperties;
import com.fakejira.config.RateLimitFilter;
import com.fakejira.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side sign-in sessions. Every request's token is checked against its session (cached briefly),
 * so signing a device out takes effect immediately.
 */
@Service
public class SessionService {

    private static final Duration CACHE_FOR = Duration.ofSeconds(30);
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(5);

    private record Cached(boolean active, long userId, Instant checkedAt) {
    }

    private final UserSessionRepository sessions;
    private final JwtProperties jwt;
    private final RateLimitFilter clientIps;
    private final TransactionTemplate tx;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> touched = new ConcurrentHashMap<>();

    public SessionService(UserSessionRepository sessions, JwtProperties jwt, RateLimitFilter clientIps,
                          TransactionTemplate tx) {
        this.sessions = sessions;
        this.jwt = jwt;
        this.clientIps = clientIps;
        this.tx = tx;
    }

    /** Starts a session for the current request's device. */
    public UserSession start(User user, String method) {
        String userAgent = null;
        String ip = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            userAgent = request.getHeader("User-Agent");
            ip = clientIps.clientIp(request);
        }
        UserSession session = new UserSession(UUID.randomUUID().toString(), user,
                Instant.now().plus(jwt.validity()), cut(userAgent, 250), cut(ip, 64), method);
        return tx.execute(status -> sessions.save(session));
    }

    /** Whether the token's session is still signed in; also records when the device was last seen. */
    public boolean isActive(String sessionId, long userId) {
        Instant now = Instant.now();
        Cached cached = cache.get(sessionId);
        if (cached == null || cached.checkedAt().plus(CACHE_FOR).isBefore(now)) {
            cached = tx.execute(status -> sessions.findById(sessionId)
                    .map(s -> new Cached(s.isActive(now), s.getUser().getId(), now))
                    .orElse(new Cached(false, -1, now)));
            cache.put(sessionId, cached);
        }
        boolean active = cached.active() && cached.userId() == userId;
        if (active) {
            Instant last = touched.get(sessionId);
            if (last == null || last.plus(TOUCH_EVERY).isBefore(now)) {
                touched.put(sessionId, now);
                tx.executeWithoutResult(status -> sessions.findById(sessionId).ifPresent(s -> s.setLastSeenAt(now)));
            }
        }
        return active;
    }

    public List<UserSession> active(Long userId) {
        return sessions.findActive(userId, Instant.now());
    }

    /** Signs one of the user's devices out. Returns false when it is not theirs. */
    public boolean revoke(Long userId, String sessionId) {
        Boolean done = tx.execute(status -> sessions.findById(sessionId)
                .filter(s -> s.getUser().getId().equals(userId))
                .map(s -> {
                    s.revoke();
                    return true;
                }).orElse(false));
        cache.remove(sessionId);
        return Boolean.TRUE.equals(done);
    }

    /** Signs every device of the user out, except {@code keepSessionId} (may be null). Returns how many. */
    public int revokeAll(Long userId, String keepSessionId) {
        List<String> ids = tx.execute(status -> {
            List<UserSession> list = sessions.findActive(userId, Instant.now()).stream()
                    .filter(s -> !s.getId().equals(keepSessionId)).toList();
            list.forEach(UserSession::revoke);
            return list.stream().map(UserSession::getId).toList();
        });
        if (ids == null) {
            return 0;
        }
        ids.forEach(cache::remove);
        return ids.size();
    }

    @Scheduled(cron = "0 45 4 * * *")
    public void prune() {
        tx.executeWithoutResult(status -> sessions.deleteEnded(Instant.now().minus(Duration.ofDays(30))));
        Instant stale = Instant.now().minus(Duration.ofHours(1));
        cache.values().removeIf(c -> c.checkedAt().isBefore(stale));
        touched.values().removeIf(t -> t.isBefore(stale));
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
