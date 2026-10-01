package com.fakejira.cluster;

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What several FakeJIRA instances behind a load balancer share, through Redis ({@code APP_REDIS_URL}): live updates
 * reach browsers connected to any instance, each scheduled job runs on one instance at a time, short-lived sign-in
 * state (SAML hand-offs) works whichever instance the browser lands on, and settings changes reach every instance.
 * Without Redis all of this happens in memory, which is right for a single instance.
 */
@Service
public class Cluster {

    private static final Logger log = LoggerFactory.getLogger(Cluster.class);
    static final String PREFIX = "fakejira:";

    private record Local(String value, Instant expires) {
    }

    private final String instanceId = UUID.randomUUID().toString();
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubsub;
    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Local> local = new ConcurrentHashMap<>();

    public Cluster(@Value("${app.redis.url:}") String url) {
        if (url == null || url.isBlank()) {
            client = null;
            connection = null;
            pubsub = null;
            return;
        }
        client = RedisClient.create(url.trim());
        client.setDefaultTimeout(Duration.ofSeconds(5));
        connection = client.connect();
        pubsub = client.connectPubSub();
        pubsub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                deliver(channel.substring(PREFIX.length()), message);
            }
        });
        log.info("Clustering through Redis is on (instance {})", instanceId.substring(0, 8));
    }

    public boolean enabled() {
        return client != null;
    }

    public String instanceId() {
        return instanceId;
    }

    // ---- Messages ----------------------------------------------------------------------------------------------------

    /** Receives every message published on {@code channel}, by this or any other instance. */
    public void subscribe(String channel, Consumer<String> handler) {
        boolean first = !subscribers.containsKey(channel);
        subscribers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(handler);
        if (first && pubsub != null) {
            pubsub.sync().subscribe(PREFIX + channel);
        }
    }

    /** Sends to all instances; if Redis is unreachable it is at least delivered here. */
    public void publish(String channel, String message) {
        if (connection == null) {
            deliver(channel, message);
            return;
        }
        try {
            connection.sync().publish(PREFIX + channel, message);
        } catch (RuntimeException e) {
            log.warn("Could not publish to Redis ({}); delivering locally only", e.getMessage());
            deliver(channel, message);
        }
    }

    private void deliver(String channel, String message) {
        for (Consumer<String> handler : subscribers.getOrDefault(channel, List.of())) {
            try {
                handler.accept(message);
            } catch (RuntimeException e) {
                log.warn("Cluster message handler for {} failed", channel, e);
            }
        }
    }

    // ---- Locks -------------------------------------------------------------------------------------------------------

    /**
     * Claims {@code name} for {@code hold}; true when this instance got it. The claim is not released early, so
     * other instances whose schedule fires at about the same moment skip the job.
     */
    public boolean claim(String name, Duration hold) {
        if (connection == null) {
            return true;
        }
        try {
            return "OK".equals(connection.sync().set(PREFIX + "lock:" + name, instanceId, SetArgs.Builder.nx().px(hold.toMillis())));
        } catch (RuntimeException e) {
            log.warn("Could not reach Redis for lock {} ({}); running the job here", name, e.getMessage());
            return true;
        }
    }

    // ---- Shared values -----------------------------------------------------------------------------------------------

    public void put(String key, String value, Duration ttl) {
        if (connection == null) {
            local.put(key, new Local(value, Instant.now().plus(ttl)));
            local.values().removeIf(v -> v.expires().isBefore(Instant.now()));
            return;
        }
        connection.sync().set(PREFIX + key, value, SetArgs.Builder.px(ttl.toMillis()));
    }

    /** Stores the value only when the key is not set yet; true when it was stored. */
    public boolean putIfAbsent(String key, String value, Duration ttl) {
        if (connection == null) {
            local.values().removeIf(v -> v.expires().isBefore(Instant.now()));
            return local.putIfAbsent(key, new Local(value, Instant.now().plus(ttl))) == null;
        }
        return "OK".equals(connection.sync().set(PREFIX + key, value, SetArgs.Builder.nx().px(ttl.toMillis())));
    }

    /** Reads and removes a value (each value can be taken once). */
    public String take(String key) {
        if (connection == null) {
            Local value = local.remove(key);
            return value == null || value.expires().isBefore(Instant.now()) ? null : value.value();
        }
        return connection.sync().getdel(PREFIX + key);
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                pubsub.close();
                connection.close();
            } finally {
                client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
            }
        }
    }
}
