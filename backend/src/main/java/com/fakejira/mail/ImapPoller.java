package com.fakejira.mail;

import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Polls a mailbox over IMAPS for replies and new-task emails when APP_MAIL_INBOUND_IMAP_HOST is set. */
@Component
public class ImapPoller {

    private static final Logger log = LoggerFactory.getLogger(ImapPoller.class);
    private static final int BATCH = 50;

    private final InboundMail inbound;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String folder;

    public ImapPoller(InboundMail inbound,
                      @Value("${app.mail.inbound.imap.host:}") String host,
                      @Value("${app.mail.inbound.imap.port:993}") int port,
                      @Value("${app.mail.inbound.imap.username:}") String username,
                      @Value("${app.mail.inbound.imap.password:}") String password,
                      @Value("${app.mail.inbound.imap.folder:INBOX}") String folder) {
        this.inbound = inbound;
        this.host = host.trim();
        this.port = port;
        this.username = username;
        this.password = password;
        this.folder = folder;
    }

    @Scheduled(fixedDelayString = "${app.mail.inbound.imap.poll-ms:60000}", initialDelay = 30_000)
    public void poll() {
        if (host.isEmpty() || !inbound.isEnabled()) {
            return;
        }
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.connectiontimeout", "15000");
        props.put("mail.imaps.timeout", "30000");
        try {
            Store store = Session.getInstance(props).getStore("imaps");
            store.connect(host, port, username, password);
            try {
                Folder inbox = store.getFolder(folder);
                inbox.open(Folder.READ_WRITE);
                try {
                    Message[] unseen = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                    for (int i = 0; i < Math.min(BATCH, unseen.length); i++) {
                        handle(unseen[i]);
                    }
                } finally {
                    inbox.close(false);
                }
            } finally {
                store.close();
            }
        } catch (MessagingException e) {
            log.warn("Could not read the inbound mailbox {}: {}", host, e.getMessage());
        }
    }

    private void handle(Message message) {
        try {
            List<String> to = new ArrayList<>();
            Address[] recipients = message.getAllRecipients();
            if (recipients != null) {
                for (Address a : recipients) {
                    to.add(a.toString());
                }
            }
            String[] delivered = message.getHeader("Delivered-To");
            if (delivered != null) {
                to.addAll(List.of(delivered));
            }
            Address[] from = message.getFrom();
            inbound.process(from == null || from.length == 0 ? "" : from[0].toString(), to, message.getSubject(), text(message));
        } catch (MessagingException | IOException | RuntimeException e) {
            log.warn("Could not process an inbound email: {}", e.getMessage());
        } finally {
            try {
                message.setFlag(Flags.Flag.SEEN, true);
            } catch (MessagingException ignored) {
                // Seen on the next poll again at worst.
            }
        }
    }

    /** The first text/plain part (or stripped HTML as a fallback). */
    static String text(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return String.valueOf(part.getContent());
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            String html = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                Part child = multipart.getBodyPart(i);
                if (child.isMimeType("text/plain")) {
                    return String.valueOf(child.getContent());
                }
                String nested = child.isMimeType("multipart/*") ? text(child) : null;
                if (nested != null && !nested.isBlank()) {
                    return nested;
                }
                if (child.isMimeType("text/html")) {
                    html = String.valueOf(child.getContent());
                }
            }
            return html == null ? "" : html.replaceAll("(?s)<[^>]+>", " ").replaceAll("[ \\t]+", " ");
        }
        if (part.isMimeType("text/html")) {
            return String.valueOf(part.getContent()).replaceAll("(?s)<[^>]+>", " ");
        }
        return "";
    }
}
