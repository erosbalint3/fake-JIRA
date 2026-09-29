package com.fakejira.mail;

import com.fakejira.admin.AppSettings;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Sends plain-text email when SMTP is configured (spring.mail.host); otherwise does nothing.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final String SITE_URL = "site.url";
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.-]+(:\\d{1,5})?$");

    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;
    private final String baseUrl;
    private final AppSettings settings;
    private volatile String learnedUrl;

    public MailService(ObjectProvider<JavaMailSender> sender,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${app.mail.from:FakeJIRA <no-reply@localhost>}") String from,
                       @Value("${app.base-url:}") String baseUrl,
                       AppSettings settings) {
        this.sender = sender;
        this.host = host;
        this.from = from;
        this.baseUrl = trimSlash(baseUrl.trim());
        this.settings = settings;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** True when APP_BASE_URL is set; otherwise links use the address admins open the app at. */
    public boolean isBaseUrlConfigured() {
        return !baseUrl.isEmpty();
    }

    /** The public URL used in links: APP_BASE_URL, else the URL learned from admin visits, else localhost. */
    public String siteUrl() {
        if (!baseUrl.isEmpty()) {
            return baseUrl;
        }
        String learned = learnedUrl;
        if (learned == null) {
            learned = settings.get(SITE_URL).orElse("http://localhost:8080");
            learnedUrl = learned;
        }
        return learned;
    }

    /**
     * Remembers the address an admin reached the app at (honouring X-Forwarded-Proto/Host from the reverse
     * proxy), so emails and invite links point there when APP_BASE_URL is not set. Only called for signed-in
     * admins: taking it from arbitrary requests would let anyone poison password reset links.
     */
    public void rememberSiteUrl(HttpServletRequest request) {
        if (!baseUrl.isEmpty()) {
            return;
        }
        String proto = first(request.getHeader("X-Forwarded-Proto"), request.getScheme()).toLowerCase();
        String hostHeader = first(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (!(proto.equals("http") || proto.equals("https")) || hostHeader == null || !HOST.matcher(hostHeader).matches()) {
            return;
        }
        String url = proto + "://" + hostHeader;
        if (!url.equals(siteUrl())) {
            settings.put(SITE_URL, url);
            learnedUrl = url;
            log.info("Public URL for links set to {} (set APP_BASE_URL to fix it)", url);
        }
    }

    private static String first(String header, String fallback) {
        if (header == null || header.isBlank()) {
            return fallback;
        }
        return header.split(",")[0].trim();
    }

    public boolean isEnabled() {
        return !host.isBlank() && sender.getIfAvailable() != null;
    }

    public String link(String path) {
        return siteUrl() + path;
    }

    @Async
    public void send(String to, String subject, String body) {
        if (!isEnabled()) {
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.getObject().send(message);
        } catch (RuntimeException e) {
            log.warn("Failed to send email to {}: {}", to, e.getMessage());
        }
    }
}
