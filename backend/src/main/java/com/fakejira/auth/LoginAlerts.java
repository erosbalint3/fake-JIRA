package com.fakejira.auth;

import com.fakejira.admin.SecurityPolicy;
import com.fakejira.audit.AuditLog;
import com.fakejira.mail.MailService;
import com.fakejira.notification.NotificationService;
import com.fakejira.user.AccountService;
import com.fakejira.user.User;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in anomaly alerts: tells people when their account is used from a new browser or network, and when someone
 * keeps guessing their password. Alerts go by email and as a notification, and are written to the audit log.
 */
@Service
public class LoginAlerts {

    static final int FAILURES_BEFORE_ALERT = 5;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    static final Duration ALERT_COOLDOWN = Duration.ofHours(1);

    private final KnownDeviceRepository devices;
    private final SecurityPolicy policy;
    private final MailService mail;
    private final NotificationService notifications;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final com.fakejira.config.RateLimitFilter clientIps;
    private final Map<Long, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastFailureAlert = new ConcurrentHashMap<>();

    public LoginAlerts(KnownDeviceRepository devices, SecurityPolicy policy, MailService mail,
                       NotificationService notifications, AuditLog audit, TransactionTemplate tx,
                       com.fakejira.config.RateLimitFilter clientIps) {
        this.clientIps = clientIps;
        this.devices = devices;
        this.policy = policy;
        this.mail = mail;
        this.notifications = notifications;
        this.audit = audit;
        this.tx = tx;
    }

    /** After a successful sign-in from the current request. */
    public void signedIn(User user, String method) {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            signedIn(user, method, clientIps.clientIp(request), request.getHeader("User-Agent"));
        }
    }

    /** Same, for the given client; returns true when this was a new device for someone who had signed in before. */
    public boolean signedIn(User user, String method, String ip, String userAgent) {
        String label = describe(userAgent);
        String fingerprint = fingerprint(ip, label);
        Boolean alert = tx.execute(status -> {
            var known = devices.findByUserIdAndFingerprint(user.getId(), fingerprint);
            if (known.isPresent()) {
                known.get().seen();
                return false;
            }
            boolean hadDevices = devices.existsByUserId(user.getId());
            devices.save(new KnownDevice(user.getId(), fingerprint, label));
            return hadDevices;
        });
        if (!Boolean.TRUE.equals(alert)) {
            return false;
        }
        audit.record(user, "login.new_device", user.getUsername(), label + " from " + ip + " via " + method);
        if (policy.get().loginAlerts()) {
            String message = "New sign-in to your account: " + label + " from " + ip + ". If this wasn't you, change your "
                    + "password and sign out other devices in your profile.";
            notifications.notifySelf(user, message, null);
            mail.send(user.getEmail(), "New sign-in to FakeJIRA",
                    "Hi " + user.getDisplayName() + ",\n\nYour FakeJIRA account was just used to sign in from a browser or network "
                            + "we haven't seen before:\n\n  " + label + "\n  " + ip + "\n  " + Instant.now() + "\n\n"
                            + "If this was you, there's nothing to do. If not, change your password and sign out the other "
                            + "devices: " + mail.link("/profile") + "\n");
        }
        return true;
    }

    /** After a wrong password for an existing account. */
    public void failed(User user, String ip) {
        Instant now = Instant.now();
        Deque<Instant> recent = failures.computeIfAbsent(user.getId(), id -> new ArrayDeque<>());
        int count;
        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(FAILURE_WINDOW))) {
                recent.pollFirst();
            }
            recent.addLast(now);
            count = recent.size();
        }
        if (count < FAILURES_BEFORE_ALERT) {
            return;
        }
        Instant last = lastFailureAlert.get(user.getId());
        if (last != null && last.plus(ALERT_COOLDOWN).isAfter(now)) {
            return;
        }
        lastFailureAlert.put(user.getId(), now);
        audit.record(user.getId(), user.getUsername(), "login.repeated_failures", user.getUsername(),
                count + " wrong passwords in 15 minutes, latest from " + ip);
        if (policy.get().loginAlerts()) {
            notifications.notifySelf(user, "Someone entered a wrong password for your account " + count
                    + " times in the last 15 minutes (latest from " + ip + ").", null);
            mail.send(user.getEmail(), "Failed sign-in attempts on your FakeJIRA account",
                    "Hi " + user.getDisplayName() + ",\n\nSomeone entered a wrong password for your account " + count
                            + " times in the last 15 minutes, most recently from " + ip + ".\n\nIf this wasn't you, consider "
                            + "turning on two-factor authentication: " + mail.link("/profile") + "\n");
        }
    }

    /** "Chrome on macOS" and so on, from the User-Agent header. */
    static String describe(String userAgent) {
        String ua = userAgent == null ? "" : userAgent;
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") ? "Opera" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : ua.contains("curl/") ? "curl"
                : ua.isBlank() ? "Unknown browser" : "Another app";
        String os = ua.contains("Windows") ? "Windows" : ua.contains("iPhone") || ua.contains("iPad") ? "iOS"
                : ua.contains("Mac OS X") || ua.contains("Macintosh") ? "macOS" : ua.contains("Android") ? "Android"
                : ua.contains("Linux") ? "Linux" : null;
        return os == null ? browser : browser + " on " + os;
    }

    /** The /24 (IPv4) or /48 (IPv6) network, so a changing address within one network is not "new". */
    static String network(String ip) {
        byte[] address = ip == null ? null : SecurityPolicy.address(ip);
        if (address == null) {
            return "unknown";
        }
        int keep = address.length == 4 ? 3 : 6;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            text.append(address[i] & 0xFF).append('.');
        }
        return text.toString();
    }

    static String fingerprint(String ip, String label) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((network(ip) + "|" + label).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @EventListener
    public void onUserDeleting(AccountService.UserDeleting event) {
        devices.deleteForUser(event.userId());
        failures.remove(event.userId());
    }

    @Scheduled(cron = "0 50 4 * * *")
    public void prune() {
        tx.executeWithoutResult(status -> devices.deleteUnusedSince(Instant.now().minus(Duration.ofDays(365))));
        Instant stale = Instant.now().minus(FAILURE_WINDOW);
        failures.values().removeIf(d -> {
            synchronized (d) {
                return d.isEmpty() || d.peekLast().isBefore(stale);
            }
        });
        lastFailureAlert.values().removeIf(t -> t.isBefore(Instant.now().minus(ALERT_COOLDOWN)));
    }
}
