package com.fakejira.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps the open SSE streams per user and fans events out to them. */
@Component
public class LiveEventHub {

    private static final Logger log = LoggerFactory.getLogger(LiveEventHub.class);
    /** Clients reconnect automatically when a stream ends. */
    private static final long STREAM_TIMEOUT = Duration.ofMinutes(30).toMillis();

    private final Map<Long, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final com.fakejira.cluster.Cluster cluster;

    /** How a live event travels between instances. */
    record Wire(java.util.Set<Long> recipients, String type, String payload) {
    }

    public LiveEventHub(ObjectMapper json, com.fakejira.cluster.Cluster cluster) {
        this.json = json;
        this.cluster = cluster;
        if (cluster.enabled()) {
            // Every instance delivers to the browsers connected to it.
            cluster.subscribe("live", message -> {
                try {
                    Wire wire = json.readValue(message, Wire.class);
                    deliver(wire.recipients(), wire.type(), wire.payload());
                } catch (IOException e) {
                    log.warn("Ignoring a malformed live event from another instance");
                }
            });
        }
    }

    public SseEmitter connect(Long userId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT);
        List<SseEmitter> userStreams = streams.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>());
        userStreams.add(emitter);
        Runnable remove = () -> userStreams.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data("{}"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onEvent(LiveEvent event) {
        String payload;
        try {
            payload = json.writeValueAsString(event.data());
        } catch (IOException e) {
            log.warn("Could not serialize live event {}", event.type(), e);
            return;
        }
        if (cluster.enabled()) {
            try {
                cluster.publish("live", json.writeValueAsString(new Wire(event.recipients(), event.type(), payload)));
                return;
            } catch (IOException e) {
                log.warn("Could not share live event {}", event.type(), e);
            }
        }
        deliver(event.recipients(), event.type(), payload);
    }

    private void deliver(java.util.Set<Long> recipients, String type, String payload) {
        for (Long userId : recipients) {
            for (SseEmitter emitter : streams.getOrDefault(userId, List.of())) {
                send(userId, emitter, SseEmitter.event().name(type).data(payload));
            }
        }
    }

    /**
     * Ends all streams when the app shuts down; otherwise graceful shutdown would wait
     * for these never-ending requests. Browsers reconnect to the new instance.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        streams.values().forEach(emitters -> emitters.forEach(SseEmitter::complete));
        streams.clear();
    }

    /** Keeps idle connections open through proxies such as Cloudflare (which cut idle streams at ~100s). */
    @com.fakejira.cluster.PerInstance
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        streams.forEach((userId, emitters) ->
                emitters.forEach(emitter -> send(userId, emitter, SseEmitter.event().comment("ping"))));
    }

    private void send(Long userId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            streams.getOrDefault(userId, List.of()).remove(emitter);
        }
    }
}
