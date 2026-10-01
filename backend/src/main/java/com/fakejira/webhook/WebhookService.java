package com.fakejira.webhook;

import com.fakejira.integration.ChatSender;
import com.fakejira.mail.MailService;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Task;
import com.fakejira.task.TaskEvent;
import com.fakejira.task.TaskRepository;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends task events to project webhooks: signed JSON, a few retries, a short delivery log. */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    static final int ATTEMPTS = 3;
    static final int LOG_SIZE = 20;

    private final WebhookRepository webhooks;
    private final WebhookDeliveryRepository deliveries;
    private final TaskRepository tasks;
    private final UserRepository users;
    private final ChatSender urls;
    private final MailService links;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final long retryDelayMs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    // One thread keeps events in the order they happened.
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webhooks");
        t.setDaemon(true);
        return t;
    });

    public WebhookService(WebhookRepository webhooks, WebhookDeliveryRepository deliveries, TaskRepository tasks,
                          UserRepository users, ChatSender urls, MailService links, ObjectMapper json, TransactionTemplate tx,
                          @Value("${app.webhooks.retry-delay-ms:2000}") long retryDelayMs) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.tasks = tasks;
        this.users = users;
        this.urls = urls;
        this.links = links;
        this.json = json;
        this.tx = tx;
        this.retryDelayMs = retryDelayMs;
    }

    @PreDestroy
    void stop() {
        sender.shutdown();
    }

    /** Builds payloads now (the task may be gone after a delete) and sends them once the change is committed. */
    @EventListener
    public void onTaskEvent(TaskEvent event) {
        List<Webhook> targets = webhooks.findByProjectIdOrderByIdAsc(event.projectId()).stream()
                .filter(w -> w.wants(event.kind().wireName())).toList();
        if (targets.isEmpty()) {
            return;
        }
        Task task = tasks.findById(event.taskId()).orElse(null);
        if (task == null) {
            return;
        }
        User actor = event.actorId() == null ? null : users.findById(event.actorId()).orElse(null);
        String body = payload(event.kind().wireName(), task, actor, event.details(), event.automated());
        List<Long> ids = targets.stream().map(Webhook::getId).toList();
        Runnable send = () -> ids.forEach(id -> sender.submit(() -> deliver(id, event.kind().wireName(), body)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    /** Sends a "ping" right away; returns the delivery (for the Test button). */
    public WebhookDelivery ping(Long webhookId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "ping");
        body.put("timestamp", Instant.now().toString());
        body.put("message", "Webhooks are working.");
        return deliver(webhookId, "ping", write(body));
    }

    WebhookDelivery deliver(Long webhookId, String event, String body) {
        Webhook hook = tx.execute(status -> webhooks.findById(webhookId).orElse(null));
        if (hook == null) {
            return null;
        }
        long started = System.nanoTime();
        Integer status = null;
        String error = null;
        int attempt = 0;
        while (attempt < ATTEMPTS) {
            attempt++;
            try {
                urls.checkUrl(hook.getUrl());
                HttpRequest request = HttpRequest.newBuilder(URI.create(hook.getUrl().trim()))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "FakeJIRA-Webhooks/1")
                        .header("X-FakeJIRA-Event", event)
                        .header("X-FakeJIRA-Delivery", UUID.randomUUID().toString())
                        .header("X-FakeJIRA-Signature", "sha256=" + sign(hook.getSecret(), body))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                status = response.statusCode();
                error = status >= 300 ? "HTTP " + status : null;
                if (status < 500) {
                    break;
                }
            } catch (com.fakejira.common.ApiException e) {
                error = e.getMessage();
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "Interrupted";
                break;
            } catch (Exception e) {
                status = null;
                error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
            if (attempt < ATTEMPTS) {
                try {
                    Thread.sleep(retryDelayMs * attempt * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        Integer finalStatus = status;
        String finalError = error;
        int attempts = attempt;
        if (error != null) {
            log.info("Webhook {} delivery of {} failed: {}", webhookId, event, error);
        }
        return tx.execute(s -> {
            webhooks.findById(webhookId).ifPresent(h -> h.delivered(finalStatus, finalError));
            WebhookDelivery saved = deliveries.save(new WebhookDelivery(webhookId, event, finalStatus, attempts, ms, finalError, body));
            List<WebhookDelivery> recent = deliveries.findByWebhookIdOrderByIdDesc(webhookId, PageRequest.of(0, LOG_SIZE));
            if (recent.size() == LOG_SIZE) {
                deliveries.trim(webhookId, recent.get(recent.size() - 1).getId());
            }
            return saved;
        });
    }

    /** What a delivery of {@code event} about {@code task} looks like (REST hook samples). */
    public String sample(String event, Task task, User actor) {
        return payload(event, task, actor, Map.of("key", task.getKey(), "title", task.getTitle()), false);
    }

    String payload(String event, Task task, User actor, Map<String, String> details, boolean automated) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", event);
        body.put("timestamp", Instant.now().toString());
        body.put("project", Map.of("key", task.getProject().getKey(), "name", task.getProject().getName()));
        body.put("actor", actor == null ? null : Map.of("id", actor.getId(), "username", actor.getUsername(),
                "displayName", actor.getName()));
        body.put("automated", automated);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", task.getId());
        t.put("key", task.getKey());
        t.put("title", task.getTitle());
        t.put("status", task.getStatus().name());
        t.put("priority", task.getPriority().name());
        t.put("type", task.getType().name());
        t.put("assignee", task.getAssignee() == null ? null : task.getAssignee().getUsername());
        t.put("reporter", task.getReporter().getUsername());
        t.put("labels", List.copyOf(task.getLabels()));
        t.put("dueDate", task.getDueDate() == null ? null : task.getDueDate().toString());
        t.put("storyPoints", task.getStoryPoints());
        t.put("url", links.link("/tasks/" + task.getId()));
        body.put("task", t);
        Map<String, String> extra = new LinkedHashMap<>(details);
        extra.remove("key");
        extra.remove("title");
        body.put("details", extra);
        return write(body);
    }

    private String write(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        for (Webhook hook : webhooks.findByProjectIdOrderByIdAsc(event.projectId())) {
            deliveries.deleteForWebhook(hook.getId());
            webhooks.delete(hook);
        }
    }
}
