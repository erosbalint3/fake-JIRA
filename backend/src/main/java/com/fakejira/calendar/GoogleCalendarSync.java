package com.fakejira.calendar;

import com.fakejira.common.ApiException;
import com.fakejira.events.LiveEvents;
import com.fakejira.mail.MailService;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.AccountService;
import com.fakejira.user.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Two-way sync between FakeJIRA and Google Calendar: open tasks assigned to someone with a due date become all-day
 * events in their calendar; moving such an event in Google Calendar moves the task's due date.
 */
@Service
public class GoogleCalendarSync {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarSync.class);
    static final String SCOPE = "https://www.googleapis.com/auth/calendar.events";

    private final CalendarConnectionRepository connections;
    private final CalendarEventLinkRepository links;
    private final TaskSupport taskSupport;
    private final LiveEvents live;
    private final MailService mail;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final String clientId;
    private final String clientSecret;
    private final String authUrl;
    private final String tokenUrl;
    private final String apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GoogleCalendarSync(CalendarConnectionRepository connections, CalendarEventLinkRepository links, TaskSupport taskSupport,
                              LiveEvents live, MailService mail, TransactionTemplate tx, ObjectMapper json,
                              @Value("${app.google-calendar.client-id:${app.oauth.google.client-id:}}") String clientId,
                              @Value("${app.google-calendar.client-secret:${app.oauth.google.client-secret:}}") String clientSecret,
                              @Value("${app.google-calendar.auth-url:https://accounts.google.com/o/oauth2/v2/auth}") String authUrl,
                              @Value("${app.google-calendar.token-url:https://oauth2.googleapis.com/token}") String tokenUrl,
                              @Value("${app.google-calendar.api-url:https://www.googleapis.com/calendar/v3}") String apiUrl) {
        this.connections = connections;
        this.links = links;
        this.taskSupport = taskSupport;
        this.live = live;
        this.mail = mail;
        this.tx = tx;
        this.json = json;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.authUrl = authUrl;
        this.tokenUrl = tokenUrl;
        this.apiUrl = apiUrl.replaceAll("/+$", "");
    }

    public boolean isAvailable() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    String redirectUri() {
        return mail.link("/api/integrations/google-calendar/callback");
    }

    public String authorizeUrl(String state) {
        return authUrl + "?response_type=code&access_type=offline&prompt=consent&include_granted_scopes=true"
                + "&client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirectUri()) + "&scope=" + enc(SCOPE) + "&state=" + enc(state);
    }

    /** Exchanges the authorization code; returns the refresh token. */
    public JsonNode exchange(String code) {
        return form(tokenUrl, Map.of("grant_type", "authorization_code", "code", code, "client_id", clientId,
                "client_secret", clientSecret, "redirect_uri", redirectUri()));
    }

    // ---- Sync ------------------------------------------------------------------------------------------------------

    public record Result(int pulled, int created, int updated, int removed) {
    }

    @Scheduled(fixedDelayString = "${app.google-calendar.sync-ms:600000}", initialDelay = 120_000)
    public void syncAll() {
        if (!isAvailable()) {
            return;
        }
        List<Long> users = tx.execute(status -> connections.findAllWithUser().stream().map(c -> c.getUser().getId()).toList());
        for (Long userId : users == null ? List.<Long>of() : users) {
            try {
                sync(userId);
            } catch (RuntimeException e) {
                log.warn("Google Calendar sync failed for user {}: {}", userId, e.getMessage());
            }
        }
    }

    /** Syncs one user: first takes date changes made in Google Calendar, then writes FakeJIRA's changes. */
    public Result sync(Long userId) {
        ApiException[] failure = new ApiException[1];
        Result result = tx.execute(status -> {
            CalendarConnection connection = connections.findByUserId(userId)
                    .orElseThrow(() -> ApiException.notFound("Google Calendar is not connected."));
            try {
                Result r = syncConnection(connection);
                connection.synced(Instant.now(), null);
                return r;
            } catch (ApiException e) {
                // Keep what was already done (events created so far) and remember the error for the profile page.
                connection.synced(Instant.now(), e.getMessage());
                failure[0] = e;
                return null;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return result;
    }

    private Result syncConnection(CalendarConnection connection) {
        User user = connection.getUser();
        String token = accessToken(connection);
        String events = apiUrl + "/calendars/" + enc(connection.getCalendarId()) + "/events";
        Map<String, CalendarEventLink> byEvent = links.forUser(user.getId()).stream()
                .collect(Collectors.toMap(CalendarEventLink::getEventId, l -> l, (a, b) -> a, LinkedHashMap::new));

        // 1. Pull: events of ours that changed in Google Calendar since the last sync.
        int pulled = 0;
        StringBuilder query = new StringBuilder("?privateExtendedProperty=").append(enc("fakejira=1")).append("&showDeleted=true&maxResults=2500");
        if (connection.getLastSyncAt() != null) {
            query.append("&updatedMin=").append(enc(connection.getLastSyncAt().minusSeconds(60).toString()));
        }
        JsonNode changed = request("GET", events + query, token, null);
        for (JsonNode event : changed.path("items")) {
            CalendarEventLink link = byEvent.get(event.path("id").asText());
            if (link == null) {
                continue;
            }
            Task task = link.getTask();
            if ("cancelled".equals(event.path("status").asText())) {
                // Deleted in Google Calendar: stop mirroring it, leave the task alone.
                links.delete(link);
                byEvent.remove(link.getEventId());
                continue;
            }
            String date = event.path("start").path("date").asText("");
            if (date.isEmpty()) {
                continue;
            }
            LocalDate due = LocalDate.parse(date);
            if (!due.equals(link.getLastDue()) && !due.equals(task.getDueDate()) && task.getProject().canEdit(user)) {
                taskSupport.record(task, user, "moved the due date to " + due + " in Google Calendar");
                task.setDueDate(due);
                link.remember(due, link.getLastSummary());
                live.taskChanged(task);
                pulled++;
            }
        }

        // 2. Push: what should be in the calendar.
        Map<Long, CalendarEventLink> byTask = new HashMap<>();
        byEvent.values().forEach(l -> byTask.put(l.getTask().getId(), l));
        int created = 0;
        int updated = 0;
        for (Task task : links.dueTasks(user.getId())) {
            if (!task.getProject().hasMember(user)) {
                continue;
            }
            String summary = task.getKey() + ": " + task.getTitle();
            CalendarEventLink link = byTask.remove(task.getId());
            if (link == null) {
                JsonNode event = request("POST", events, token, event(task, summary));
                CalendarEventLink saved = new CalendarEventLink(user, task, event.path("id").asText());
                saved.remember(task.getDueDate(), summary);
                links.save(saved);
                created++;
            } else if (!task.getDueDate().equals(link.getLastDue()) || !summary.equals(link.getLastSummary())) {
                request("PATCH", events + "/" + enc(link.getEventId()), token, event(task, summary));
                link.remember(task.getDueDate(), summary);
                updated++;
            }
        }
        // 3. Remove events for tasks that are done, unassigned or no longer have a due date.
        int removed = 0;
        for (CalendarEventLink stale : byTask.values()) {
            try {
                request("DELETE", events + "/" + enc(stale.getEventId()), token, null);
            } catch (ApiException e) {
                if (!e.getMessage().contains("404") && !e.getMessage().contains("410")) {
                    throw e;
                }
            }
            links.delete(stale);
            removed++;
        }
        return new Result(pulled, created, updated, removed);
    }

    private Map<String, Object> event(Task task, String summary) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", summary);
        body.put("description", "Open in FakeJIRA: " + mail.link("/tasks/" + task.getId()));
        body.put("start", Map.of("date", task.getDueDate().toString()));
        body.put("end", Map.of("date", task.getDueDate().plusDays(1).toString()));
        body.put("transparency", "transparent");
        body.put("source", Map.of("title", "FakeJIRA", "url", mail.link("/tasks/" + task.getId())));
        body.put("extendedProperties", Map.of("private", Map.of("fakejira", "1", "fakejiraTask", String.valueOf(task.getId()))));
        return body;
    }

    private String accessToken(CalendarConnection connection) {
        if (connection.getAccessToken() != null && connection.getAccessExpiresAt() != null
                && connection.getAccessExpiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return connection.getAccessToken();
        }
        JsonNode token = form(tokenUrl, Map.of("grant_type", "refresh_token", "refresh_token", connection.getRefreshToken(),
                "client_id", clientId, "client_secret", clientSecret));
        String access = token.path("access_token").asText(null);
        if (access == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google did not issue an access token; connect the calendar again.");
        }
        connection.setAccess(access, Instant.now().plusSeconds(token.path("expires_in").asLong(3600)));
        return access;
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------------

    private JsonNode form(String url, Map<String, String> fields) {
        String body = fields.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)), "Google sign-in");
    }

    private JsonNode request(String method, String url, String token, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token);
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            return send(request, "Google Calendar");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode send(HttpRequest.Builder request, String what) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body() == null || response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
            if (response.statusCode() >= 300) {
                String message = body.path("error").isObject() ? body.path("error").path("message").asText()
                        : body.path("error_description").asText(body.path("error").asText(""));
                throw new ApiException(HttpStatus.BAD_GATEWAY, what + " (" + response.statusCode() + "): " + message);
            }
            return body;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach " + what + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Interrupted while talking to " + what + ".");
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        // The next sync cannot find the task any more, so the event is removed here from the link only;
        // the calendar keeps a stale event until the user deletes it (rare, and harmless).
        links.deleteForTask(event.taskId());
    }

    @EventListener
    public void onUserDeleting(AccountService.UserDeleting event) {
        links.deleteForUser(event.userId());
        connections.findByUserId(event.userId()).ifPresent(connections::delete);
    }
}
