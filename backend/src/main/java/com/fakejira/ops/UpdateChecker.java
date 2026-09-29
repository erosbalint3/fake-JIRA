package com.fakejira.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/** Checks once a day whether a newer FakeJIRA release exists (can be turned off). */
@Service
public class UpdateChecker {

    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);

    private final boolean enabled;
    private final String url;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private volatile String latestVersion;
    private volatile String releaseUrl;
    private volatile Instant checkedAt;
    private volatile String error;

    public UpdateChecker(@Value("${app.update-check.enabled:true}") boolean enabled,
                         @Value("${app.update-check.url:https://api.github.com/repos/erosbalint3/fake-JIRA/releases/latest}") String url,
                         ObjectMapper json) {
        this.enabled = enabled;
        this.url = url;
        this.json = json;
    }

    public record Update(boolean enabled, String current, String latest, boolean available, String url, Instant checkedAt,
                         String error) {
    }

    public Update status() {
        String current = SystemController.version();
        return new Update(enabled, current, latestVersion, latestVersion != null && newer(latestVersion, current), releaseUrl,
                checkedAt, error);
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        check();
    }

    @Scheduled(cron = "${app.update-check.cron:0 17 4 * * *}")
    public void check() {
        if (!enabled) {
            return;
        }
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "FakeJIRA-update-check").build(),
                    HttpResponse.BodyHandlers.ofString());
            checkedAt = Instant.now();
            if (response.statusCode() == 404) {
                error = null;
                latestVersion = null;
                return;
            }
            if (response.statusCode() != 200) {
                error = "HTTP " + response.statusCode();
                return;
            }
            JsonNode release = json.readTree(response.body());
            latestVersion = release.path("tag_name").asText("").replaceFirst("^v", "");
            releaseUrl = release.path("html_url").asText(null);
            error = null;
            if (newer(latestVersion, SystemController.version())) {
                log.info("FakeJIRA {} is available (running {}): {}", latestVersion, SystemController.version(), releaseUrl);
            }
        } catch (Exception e) {
            checkedAt = Instant.now();
            error = e.getClass().getSimpleName();
        }
    }

    /** True when version {@code a} is newer than {@code b} (numeric parts compared, e.g. 4.10.0 > 4.9.2). */
    static boolean newer(String a, String b) {
        if (a == null || a.isBlank() || b == null || b.isBlank() || b.equals("dev")) {
            return false;
        }
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0;
            int q = i < y.length ? parse(y[i]) : 0;
            if (p != q) {
                return p > q;
            }
        }
        return false;
    }

    private static int parse(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
