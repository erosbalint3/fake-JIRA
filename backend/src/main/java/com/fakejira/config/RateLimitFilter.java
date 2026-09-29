package com.fakejira.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fakejira.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window rate limits for the unauthenticated auth endpoints, per client IP
 * (and per account name for logins, to slow down guessing from many IPs).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String path, int limit, Duration window) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("/api/auth/login", 10, Duration.ofMinutes(1)),
            new Rule("/api/auth/login/2fa", 10, Duration.ofMinutes(1)),
            new Rule("/api/auth/register", 5, Duration.ofHours(1)),
            new Rule("/api/auth/forgot-password", 5, Duration.ofHours(1)),
            new Rule("/api/auth/reset-password", 10, Duration.ofHours(1)));

    private static final int LOGIN_PER_ACCOUNT = 20;
    private static final Duration LOGIN_ACCOUNT_WINDOW = Duration.ofMinutes(15);

    private record Window(long startedAt, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final List<String> ipHeaders;
    private final ObjectMapper json;

    public RateLimitFilter(@Value("${app.rate-limit.enabled:true}") boolean enabled,
                           @Value("${app.rate-limit.client-ip-headers:CF-Connecting-IP,X-Forwarded-For}") String ipHeaders,
                           ObjectMapper json) {
        this.enabled = enabled;
        this.ipHeaders = Arrays.stream(ipHeaders.split(",")).map(String::trim).filter(h -> !h.isEmpty()).toList();
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        for (Rule rule : RULES) {
            if (path.equals(rule.path())) {
                long retry = hit(rule.path() + "|ip|" + clientIp(request), rule.limit(), rule.window());
                if (retry > 0) {
                    reject(response, retry);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    /** Counts a login attempt against the account name; called by the auth service. */
    public void checkLoginAccount(String login) {
        long retry = hit("login|account|" + login.trim().toLowerCase(Locale.ROOT), LOGIN_PER_ACCOUNT, LOGIN_ACCOUNT_WINDOW);
        if (retry > 0) {
            throw new RateLimitedException(retry);
        }
    }

    /** Returns 0 if allowed, otherwise seconds until the window resets. */
    private long hit(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        Window current = windows.compute(key, (k, w) ->
                w == null || now - w.startedAt() >= window.toMillis() ? new Window(now, new AtomicInteger()) : w);
        if (current.count().incrementAndGet() > limit) {
            return Math.max(1, (current.startedAt() + window.toMillis() - now) / 1000);
        }
        return 0;
    }

    public String clientIp(HttpServletRequest request) {
        for (String header : ipHeaders) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                return value.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
                new ErrorResponse("Too many attempts. Try again in " + friendly(retryAfterSeconds) + "."));
    }

    static String friendly(long seconds) {
        return seconds < 90 ? seconds + " seconds" : Math.round(seconds / 60.0) + " minutes";
    }

    @Scheduled(fixedRate = 600_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() > Duration.ofHours(2).toMillis());
    }

    /** Thrown when a per-account limit is hit; mapped to 429 by the exception handler. */
    public static class RateLimitedException extends RuntimeException {
        private final long retryAfterSeconds;

        public RateLimitedException(long retryAfterSeconds) {
            super("Too many attempts. Try again in " + friendly(retryAfterSeconds) + ".");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
