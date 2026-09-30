package com.fakejira.integration;

import com.fakejira.common.ApiException;
import com.fakejira.integration.ChatNotifier.ChatMessage;
import com.fakejira.mail.MailService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Posts {@link ChatMessage}s to the project's chat webhooks once the change is committed. */
@Component
public class ChatSender {

    private static final Logger log = LoggerFactory.getLogger(ChatSender.class);

    private final ChatHookRepository hooks;
    private final MailService links;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final boolean allowPrivate;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ChatSender(ChatHookRepository hooks, MailService links, TransactionTemplate tx, ObjectMapper json,
                      @Value("${app.chat.allow-private-addresses:false}") boolean allowPrivate) {
        this.hooks = hooks;
        this.links = links;
        this.tx = tx;
        this.json = json;
        this.allowPrivate = allowPrivate;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onMessage(ChatMessage message) {
        List<ChatHook> targets = tx.execute(status -> hooks.findByProjectIdOrderByIdAsc(message.projectId()).stream()
                .filter(hook -> hook.getEvents().contains(message.type()))
                .toList());
        if (targets == null) {
            return;
        }
        for (ChatHook hook : targets) {
            String error = send(hook.getKind(), hook.getUrl(), message);
            tx.executeWithoutResult(status -> hooks.findById(hook.getId()).ifPresent(h -> h.delivered(error)));
        }
    }

    /** Sends one message; returns null on success or a short error description. */
    public String send(ChatHook.Kind kind, String url, ChatMessage message) {
        try {
            checkUrl(url);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload(kind, message)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return "HTTP " + response.statusCode();
            }
            return null;
        } catch (ApiException e) {
            return e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted";
        } catch (Exception e) {
            log.warn("Chat webhook delivery failed: {}", e.toString());
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
    }

    String payload(ChatHook.Kind kind, ChatMessage m) throws JsonProcessingException {
        String link = links.link(m.link());
        if (kind == ChatHook.Kind.DISCORD) {
            String text = "**" + discord(m.actor()) + "** " + m.verb() + " [" + discord(m.subject()) + "](<" + link + ">)"
                    + (m.quote() == null ? "" : "\n> " + discord(m.quote()).replace("\n", "\n> "));
            // No @everyone / role pings from task text.
            return json.writeValueAsString(Map.of("content", cut(text, 1900), "allowed_mentions", Map.of("parse", List.of())));
        }
        if (kind == ChatHook.Kind.MATTERMOST || kind == ChatHook.Kind.TEAMS) {
            // Both take Markdown; Teams wants it in a MessageCard.
            String text = "**" + markdown(m.actor()) + "** " + m.verb() + " [" + markdown(m.subject()) + "](" + link + ")"
                    + (m.quote() == null ? "" : "\n\n> " + markdown(m.quote()).replace("\n", "\n> "));
            if (kind == ChatHook.Kind.MATTERMOST) {
                return json.writeValueAsString(Map.of("text", cut(text, 3500)));
            }
            return json.writeValueAsString(Map.of("@type", "MessageCard", "@context", "https://schema.org/extensions",
                    "summary", cut(m.actor() + " " + m.verb() + " " + m.subject(), 200), "themeColor", "4F46E5",
                    "text", cut(text, 3500)));
        }
        String text = "*" + slack(m.actor()) + "* " + m.verb() + " <" + link + "|" + slack(m.subject()) + ">"
                + (m.quote() == null ? "" : "\n>" + slack(m.quote()).replace("\n", "\n>"));
        return json.writeValueAsString(Map.of("text", cut(text, 3500)));
    }

    private static String slack(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String markdown(String text) {
        return text.replaceAll("([\\\\*_`\\[\\]])", "\\\\$1").replace("@", "@\u200B");
    }

    private static String discord(String text) {
        return text.replaceAll("([\\\\*_~`|>\\[\\]()@#])", "\\\\$1");
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /**
     * Only https URLs on public addresses: the server makes these requests, so an owner must not be able to
     * point them at the server's own network.
     */
    public void checkUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("That is not a valid URL.");
        }
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                || (allowPrivate && "http".equalsIgnoreCase(uri.getScheme())))) {
            throw ApiException.badRequest("Use an https:// URL.");
        }
        if (allowPrivate) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress() || address.isMulticastAddress()
                        || isUniqueLocal(address)) {
                    throw ApiException.badRequest("Webhooks cannot point to private network addresses.");
                }
            }
        } catch (UnknownHostException e) {
            throw ApiException.badRequest("Unknown host " + uri.getHost() + ".");
        }
    }

    /** IPv6 unique-local fc00::/7 and carrier-grade NAT 100.64.0.0/10 (used by e.g. Tailscale). */
    private static boolean isUniqueLocal(InetAddress address) {
        byte[] b = address.getAddress();
        return (b.length == 16 && (b[0] & 0xfe) == 0xfc) || (b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64);
    }
}
