package com.fakejira.calendar;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Connect, sync and disconnect Google Calendar (see {@link GoogleCalendarSync}). */
@RestController
public class GoogleCalendarController {

    private record PendingState(Long userId, Instant expires) {
    }

    private final GoogleCalendarSync sync;
    private final CalendarConnectionRepository connections;
    private final CalendarEventLinkRepository links;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final Map<String, PendingState> states = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public GoogleCalendarController(GoogleCalendarSync sync, CalendarConnectionRepository connections,
                                    CalendarEventLinkRepository links, UserRepository users, CurrentUser currentUser) {
        this.sync = sync;
        this.connections = connections;
        this.links = links;
        this.users = users;
        this.currentUser = currentUser;
    }

    public record Status(boolean available, boolean connected, Instant lastSyncAt, String lastError, int events) {
    }

    @GetMapping("/api/integrations/google-calendar")
    @Transactional(readOnly = true)
    public Status status(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return connections.findByUserId(user.getId())
                .map(c -> new Status(sync.isAvailable(), true, c.getLastSyncAt(), c.getLastError(), links.forUser(user.getId()).size()))
                .orElse(new Status(sync.isAvailable(), false, null, null, 0));
    }

    @PostMapping("/api/integrations/google-calendar/connect")
    public Map<String, String> connect(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        if (!sync.isAvailable()) {
            throw ApiException.badRequest("Google Calendar is not set up on this server. Ask the administrator.");
        }
        states.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        states.put(state, new PendingState(user.getId(), Instant.now().plusSeconds(600)));
        return Map.of("url", sync.authorizeUrl(state));
    }

    /** Google sends the browser back here; the one-time state identifies who started the connection. */
    @GetMapping("/api/integrations/google-calendar/callback")
    @Transactional
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        PendingState pending = state == null ? null : states.remove(state);
        if (pending == null || pending.expires().isBefore(Instant.now())) {
            return redirect("/profile?calendar=expired#calendar");
        }
        if (error != null || code == null) {
            return redirect("/profile?calendar=denied#calendar");
        }
        var token = sync.exchange(code);
        String refresh = token.path("refresh_token").asText(null);
        if (refresh == null) {
            return redirect("/profile?calendar=failed#calendar");
        }
        User user = users.findById(pending.userId()).orElseThrow();
        CalendarConnection connection = connections.findByUserId(user.getId()).orElseGet(() -> new CalendarConnection(user, refresh));
        connection.setRefreshToken(refresh);
        connection.setAccess(token.path("access_token").asText(null), Instant.now().plusSeconds(token.path("expires_in").asLong(3600)));
        connections.save(connection);
        return redirect("/profile?calendar=connected#calendar");
    }

    @PostMapping("/api/integrations/google-calendar/sync")
    public GoogleCalendarSync.Result syncNow(@AuthenticationPrincipal Jwt jwt) {
        return sync.sync(currentUser.from(jwt).getId());
    }

    @DeleteMapping("/api/integrations/google-calendar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void disconnect(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        links.deleteForUser(user.getId());
        connections.findByUserId(user.getId()).ifPresent(connections::delete);
    }

    private static ResponseEntity<Void> redirect(String path) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(path).toString()).build();
    }
}
