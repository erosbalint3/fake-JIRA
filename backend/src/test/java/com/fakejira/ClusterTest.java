package com.fakejira;

import com.fakejira.cluster.Cluster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Two app instances sharing one Redis: messages reach both, job claims are exclusive, shared values are taken once. */
class ClusterTest {

    static Process redis;
    static String url;

    @BeforeAll
    static void startRedis() throws Exception {
        Assumptions.assumeTrue(new File("/usr/bin/redis-server").canExecute() || new File("/usr/local/bin/redis-server").canExecute(),
                "redis-server is not installed");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        redis = new ProcessBuilder("redis-server", "--port", String.valueOf(port), "--save", "", "--appendonly", "no",
                "--bind", "127.0.0.1").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        url = "redis://127.0.0.1:" + port;
        for (int i = 0; i < 50; i++) {
            try (java.net.Socket ignored = new java.net.Socket("127.0.0.1", port)) {
                return;
            } catch (java.io.IOException e) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("redis-server did not start");
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) redis.destroy();
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50 && !condition.getAsBoolean(); i++) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void instancesShareMessagesLocksAndValues() throws Exception {
        Cluster a = new Cluster(url);
        Cluster b = new Cluster(url);
        try {
            assertThat(a.enabled()).isTrue();
            List<String> atA = new CopyOnWriteArrayList<>();
            List<String> atB = new CopyOnWriteArrayList<>();
            a.subscribe("live", atA::add);
            b.subscribe("live", atB::add);
            a.publish("live", "task 7 changed");
            waitFor(() -> atA.size() == 1 && atB.size() == 1);
            assertThat(atB).containsExactly("task 7 changed");

            assertThat(a.claim("job:backup", Duration.ofSeconds(5))).isTrue();
            assertThat(b.claim("job:backup", Duration.ofSeconds(5))).isFalse();
            assertThat(b.claim("job:digest", Duration.ofMillis(200))).isTrue();
            Thread.sleep(300);
            assertThat(a.claim("job:digest", Duration.ofSeconds(5))).isTrue();

            a.put("saml:ticket:x", "profile", Duration.ofSeconds(30));
            assertThat(b.take("saml:ticket:x")).isEqualTo("profile");
            assertThat(a.take("saml:ticket:x")).isNull();
            assertThat(a.putIfAbsent("saml:assertion:1", "used", Duration.ofSeconds(30))).isTrue();
            assertThat(b.putIfAbsent("saml:assertion:1", "used", Duration.ofSeconds(30))).isFalse();
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void withoutRedisEverythingIsLocal() {
        Cluster single = new Cluster("");
        List<String> got = new CopyOnWriteArrayList<>();
        single.subscribe("live", got::add);
        single.publish("live", "hello");
        assertThat(got).containsExactly("hello");
        assertThat(single.claim("job:x", Duration.ofSeconds(5))).isTrue();
        assertThat(single.claim("job:x", Duration.ofSeconds(5))).isTrue();
        single.put("k", "v", Duration.ofSeconds(5));
        assertThat(single.take("k")).isEqualTo("v");
        assertThat(single.take("k")).isNull();
    }
}
