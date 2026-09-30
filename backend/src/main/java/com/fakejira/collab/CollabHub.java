package com.fakejira.collab;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state for people looking at or co-editing a task.
 *
 * <p>Presence: who has the task open (and whether they are editing), refreshed by heartbeats.
 * Co-editing: an append-only log of CRDT updates (Yjs, base64) per task while anyone edits it. The first
 * editor of a session seeds the document with the saved description; later editors replay the log. The log
 * is dropped once everyone has left, so the saved description stays the source of truth.
 */
@Component
public class CollabHub {

    static final Duration PRESENCE_TTL = Duration.ofSeconds(45);
    static final int MAX_UPDATES = 5000;
    static final int MAX_LOG_BYTES = 4 * 1024 * 1024;

    public record Viewer(Long userId, String clientId, boolean editing, Instant seen) {
    }

    static final class Session {
        final List<String> updates = new ArrayList<>();
        int bytes;
        String seeder;
        final Map<String, Instant> editors = new LinkedHashMap<>();
    }

    private final Map<Long, Map<String, Viewer>> presence = new ConcurrentHashMap<>();
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    /** Records a heartbeat; returns true when the set of people (or their editing state) changed. */
    public boolean heartbeat(Long taskId, Long userId, String clientId, boolean editing) {
        Map<String, Viewer> viewers = presence.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>());
        Viewer before = viewers.put(clientId, new Viewer(userId, clientId, editing, Instant.now()));
        return before == null || before.editing() != editing;
    }

    public boolean leave(Long taskId, String clientId) {
        Map<String, Viewer> viewers = presence.get(taskId);
        boolean removed = viewers != null && viewers.remove(clientId) != null;
        leaveSession(taskId, clientId);
        return removed;
    }

    public List<Viewer> viewers(Long taskId) {
        Map<String, Viewer> viewers = presence.getOrDefault(taskId, Map.of());
        Instant cutoff = Instant.now().minus(PRESENCE_TTL);
        return viewers.values().stream().filter(v -> v.seen().isAfter(cutoff)).toList();
    }

    /** Joins the editing session: {@code seed} tells this client to load the saved text into the document. */
    public synchronized JoinResult join(Long taskId, String clientId) {
        Session session = sessions.computeIfAbsent(taskId, k -> new Session());
        session.editors.put(clientId, Instant.now());
        boolean seed = session.seeder == null;
        if (seed) {
            session.seeder = clientId;
        }
        return new JoinResult(seed, List.copyOf(session.updates));
    }

    public record JoinResult(boolean seed, List<String> updates) {
    }

    /** Appends an update; false when the session is unknown (e.g. after a restart) or too large. */
    public synchronized boolean append(Long taskId, String clientId, String update) {
        Session session = sessions.get(taskId);
        if (session == null || !session.editors.containsKey(clientId)
                || session.updates.size() >= MAX_UPDATES || session.bytes + update.length() > MAX_LOG_BYTES) {
            return false;
        }
        session.editors.put(clientId, Instant.now());
        session.updates.add(update);
        session.bytes += update.length();
        return true;
    }

    public synchronized void leaveSession(Long taskId, String clientId) {
        Session session = sessions.get(taskId);
        if (session != null) {
            session.editors.remove(clientId);
            // A seeder that left before sending the saved text hands the job to the next joiner.
            if (clientId.equals(session.seeder) && session.updates.isEmpty()) {
                session.seeder = null;
            }
            if (session.editors.isEmpty()) {
                sessions.remove(taskId);
            }
        }
    }

    public synchronized int editors(Long taskId) {
        Session session = sessions.get(taskId);
        return session == null ? 0 : session.editors.size();
    }

    /** Forgets people whose browser stopped sending heartbeats (closed tab, lost network). */
    @Scheduled(fixedDelay = 30000)
    public synchronized void expire() {
        Instant cutoff = Instant.now().minus(PRESENCE_TTL);
        presence.values().forEach(viewers -> viewers.values().removeIf(v -> v.seen().isBefore(cutoff)));
        presence.values().removeIf(Map::isEmpty);
        sessions.values().forEach(s -> s.editors.values().removeIf(seen -> seen.isBefore(cutoff)));
        sessions.values().removeIf(s -> s.editors.isEmpty());
    }
}
