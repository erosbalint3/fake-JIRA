package com.fakejira.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Sends plain-text email when SMTP is configured (spring.mail.host); otherwise does nothing.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;
    private final String baseUrl;

    public MailService(ObjectProvider<JavaMailSender> sender,
                       @Value("${spring.mail.host:}") String host,
                       @Value("${app.mail.from:FakeJIRA <no-reply@localhost>}") String from,
                       @Value("${app.base-url:http://localhost:5173}") String baseUrl) {
        this.sender = sender;
        this.host = host;
        this.from = from;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public boolean isEnabled() {
        return !host.isBlank() && sender.getIfAvailable() != null;
    }

    public String link(String path) {
        return baseUrl + path;
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
