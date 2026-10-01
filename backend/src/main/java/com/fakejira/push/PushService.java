package com.fakejira.push;

import com.fakejira.admin.AppSettings;
import com.fakejira.common.ApiException;
import com.fakejira.mail.MailService;
import com.fakejira.notification.NotificationCreated;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sends browser push notifications (Web Push with VAPID) for new in-app notifications. */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    static final String PUBLIC_KEY = "vapid.public";
    static final String PRIVATE_KEY = "vapid.private";

    /** Only real browser push services may be used, so the server cannot be pointed at internal hosts. */
    static final List<String> ALLOWED_HOST_SUFFIXES = List.of(
            ".googleapis.com", ".mozilla.com", ".mozaws.net", ".notify.windows.com", ".push.apple.com");

    private final AppSettings settings;
    private final PushSubscriptionRepository subscriptions;
    private final MailService mail;
    private final ObjectMapper json;
    private final String subject;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final SecureRandom random = new SecureRandom();
    private volatile KeyPair keys;

    public PushService(AppSettings settings, PushSubscriptionRepository subscriptions, MailService mail, ObjectMapper json,
                       @Value("${app.push.subject:}") String subject) {
        this.settings = settings;
        this.subscriptions = subscriptions;
        this.mail = mail;
        this.json = json;
        this.subject = subject;
    }

    /** The VAPID key pair, generated once and stored in the database. */
    public KeyPair keys() {
        KeyPair current = keys;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (keys == null) {
                Base64.Decoder decoder = Base64.getUrlDecoder();
                try {
                    var pub = settings.get(PUBLIC_KEY);
                    var priv = settings.get(PRIVATE_KEY);
                    if (pub.isPresent() && priv.isPresent()) {
                        keys = WebPushCrypto.keyPair(decoder.decode(pub.get()), decoder.decode(priv.get()));
                    } else {
                        KeyPair generated = WebPushCrypto.generateKeyPair();
                        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
                        settings.put(PUBLIC_KEY, encoder.encodeToString(WebPushCrypto.rawPublicKey((ECPublicKey) generated.getPublic())));
                        settings.put(PRIVATE_KEY, encoder.encodeToString(WebPushCrypto.rawPrivateKey((ECPrivateKey) generated.getPrivate())));
                        keys = generated;
                    }
                } catch (GeneralSecurityException e) {
                    throw new IllegalStateException("Invalid stored VAPID keys", e);
                }
            }
            return keys;
        }
    }

    public String publicKey() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(WebPushCrypto.rawPublicKey((ECPublicKey) keys().getPublic()));
    }

    public static void validateEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Invalid push endpoint.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(uri.getScheme()) || ALLOWED_HOST_SUFFIXES.stream().noneMatch(host::endsWith)) {
            throw ApiException.badRequest("Unsupported push service.");
        }
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onNotification(NotificationCreated event) {
        if (!event.pushEnabled()) {
            return;
        }
        String url = event.taskId() == null ? "/notifications" : "/tasks/" + event.taskId();
        sendToUser(event.recipientId(), Map.of("title", "FakeJIRA", "body", event.message(), "url", url));
    }

    /** Returns how many devices accepted the message. */
    public int sendToUser(Long userId, Map<String, String> message) {
        List<PushSubscription> targets = subscriptions.findByUserId(userId);
        int delivered = 0;
        for (PushSubscription subscription : targets) {
            if (send(subscription, message)) {
                delivered++;
            }
        }
        return delivered;
    }

    private boolean send(PushSubscription subscription, Map<String, String> message) {
        try {
            validateEndpoint(subscription.getEndpoint());
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            byte[] body = WebPushCrypto.encrypt(json.writeValueAsBytes(message), decoder.decode(subscription.getP256dh()),
                    decoder.decode(subscription.getAuth()), WebPushCrypto.generateKeyPair(), salt);
            URI endpoint = URI.create(subscription.getEndpoint());
            String audience = endpoint.getScheme() + "://" + endpoint.getHost();
            String authorization = WebPushCrypto.vapidAuthorization(audience, vapidSubject(), keys(),
                    Instant.now().plus(Duration.ofHours(12)).getEpochSecond());
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", authorization)
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", "86400")
                    .header("Urgency", "normal")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 || response.statusCode() == 410) {
                // The browser unsubscribed or the subscription expired.
                subscriptions.delete(subscription);
                return false;
            }
            if (response.statusCode() >= 300) {
                log.warn("Push service answered {} for subscription {}", response.statusCode(), subscription.getId());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Push to subscription {} failed: {}", subscription.getId(), e.getMessage());
            return false;
        }
    }

    private String vapidSubject() {
        if (!subject.isBlank()) {
            return subject;
        }
        String base = mail.link("");
        return base.startsWith("https://") ? base : "mailto:admin@example.com";
    }
}
