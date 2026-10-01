package com.fakejira.integration;

import com.fakejira.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** A small GitHub REST API client authenticated with a project's personal access token. */
@Component
public class GithubApi {

    private final String baseUrl;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GithubApi(@Value("${app.github.api-url:https://api.github.com}") String baseUrl, ObjectMapper json) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.json = json;
    }

    public JsonNode get(String token, String path) {
        return call("GET", token, path, null);
    }

    public JsonNode post(String token, String path, Map<String, Object> body) {
        return call("POST", token, path, body);
    }

    public JsonNode patch(String token, String path, Map<String, Object> body) {
        return call("PATCH", token, path, body);
    }

    static String repoPath(String repo) {
        String[] parts = repo.split("/", 2);
        return "/repos/" + enc(parts[0]) + "/" + enc(parts[1]);
    }

    static String enc(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode call(String method, String token, String path, Map<String, Object> body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "FakeJIRA");
            if (token != null && !token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode result = response.body() == null || response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
            if (response.statusCode() >= 300) {
                String message = result.path("message").asText("HTTP " + response.statusCode());
                if (response.statusCode() == 401) {
                    message = "GitHub rejected the access token.";
                } else if (response.statusCode() == 404) {
                    message = "GitHub could not find that (check the repository name and the token's access).";
                }
                JsonNode errors = result.path("errors");
                if (errors.isArray() && !errors.isEmpty()) {
                    message += " " + errors.get(0).path("message").asText(errors.get(0).path("code").asText(""));
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GitHub: " + message.trim());
            }
            return result;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not reach GitHub: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Interrupted while talking to GitHub.");
        }
    }
}
