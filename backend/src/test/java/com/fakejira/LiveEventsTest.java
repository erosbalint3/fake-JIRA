package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against a real server so the SSE stream goes through the full security filter chain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveEventsTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @Test
    void membersReceiveTaskAndNotificationEventsOthersDoNot() throws Exception {
        JsonNode alice = register();
        JsonNode bob = register();
        JsonNode outsider = register();
        String key = "LV" + (System.nanoTime() % 100000);
        call("POST", "/api/projects", alice, "{\"key\":\"" + key + "\",\"name\":\"Live\"}");
        call("POST", "/api/projects/" + key + "/members", alice, "{\"login\":\"" + bob.at("/user/username").asText() + "\"}");

        BlockingQueue<String> bobEvents = listen(bob);
        BlockingQueue<String> outsiderEvents = listen(outsider);
        assertThat(bobEvents.poll(5, TimeUnit.SECONDS)).isEqualTo("ready");
        assertThat(outsiderEvents.poll(5, TimeUnit.SECONDS)).isEqualTo("ready");

        call("POST", "/api/tasks", alice, "{\"projectKey\":\"" + key + "\",\"title\":\"Live one\",\"priority\":\"LOW\","
                + "\"assigneeId\":" + bob.at("/user/id").asLong() + "}");

        List<String> received = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            received.add(bobEvents.poll(5, TimeUnit.SECONDS));
        }
        assertThat(received).containsExactlyInAnyOrder("notification", "task");
        assertThat(outsiderEvents.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void streamRequiresAuthentication() throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri("/api/events")).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private BlockingQueue<String> listen(JsonNode account) {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        HttpRequest request = HttpRequest.newBuilder(uri("/api/events"))
                .header("Authorization", "Bearer " + account.get("token").asText())
                .header("Accept", "text/event-stream").build();
        Thread reader = new Thread(() -> {
            try {
                HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.startsWith("event:")) {
                            events.add(line.substring("event:".length()).trim());
                        }
                    }
                }
            } catch (Exception ignored) {
                // stream closed when the test ends
            }
        });
        reader.setDaemon(true);
        reader.start();
        return events;
    }

    private JsonNode register() throws Exception {
        String name = "live" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return json.readTree(call("POST", "/api/auth/register", null,
                "{\"username\":\"" + name + "\",\"email\":\"" + name + "@example.com\",\"password\":\"Secret#123\"}"));
    }

    private String call(String method, String path, JsonNode account, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.get("token").asText());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " -> " + response.body()).isBetween(200, 299);
        return response.body();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
