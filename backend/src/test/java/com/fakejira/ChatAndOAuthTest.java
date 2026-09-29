package com.fakejira;

import com.fakejira.integration.ChatSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chat webhooks and "Sign in with GitHub", both against a local fake server. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatAndOAuthTest extends ApiTestSupport {

    private static HttpServer server;
    private static final List<String> chatBodies = new CopyOnWriteArrayList<>();
    private static volatile String githubUserId = "4242";
    private static volatile String githubEmail = "octo@example.com";

    @BeforeAll
    static void startFakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            chatBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, "ok");
        });
        server.createContext("/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(exchange, form.contains("code=good") ? 200 : 400,
                    form.contains("code=good") ? "{\"access_token\":\"at-1\",\"token_type\":\"bearer\"}" : "{}");
        });
        server.createContext("/user", exchange -> reply(exchange, 200,
                "{\"id\":" + githubUserId + ",\"login\":\"octo\",\"name\":\"Octo Cat\"}"));
        server.createContext("/emails", exchange -> reply(exchange, 200,
                "[{\"email\":\"" + githubEmail + "\",\"primary\":true,\"verified\":true}]"));
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        registry.add("app.chat.allow-private-addresses", () -> "true");
        registry.add("app.base-url", () -> "https://jira.example.test");
        registry.add("app.oauth.github.client-id", () -> "cid");
        registry.add("app.oauth.github.client-secret", () -> "csecret");
        registry.add("app.oauth.github.authorize-url", () -> base + "/authorize");
        registry.add("app.oauth.github.token-url", () -> base + "/token");
        registry.add("app.oauth.github.user-url", () -> base + "/user");
        registry.add("app.oauth.github.emails-url", () -> base + "/emails");
    }

    @Test
    void chatWebhooksGetTaskAndSprintUpdates() throws Exception {
        Account owner = register();
        Account member = register();
        String key = project(owner, member);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";

        perform(post("/api/projects/" + key + "/chat-hooks"), member, body("kind", "SLACK", "url", url))
                .andExpect(status().isForbidden());
        JsonNode hook = read(perform(post("/api/projects/" + key + "/chat-hooks"), owner,
                "{\"kind\":\"DISCORD\",\"url\":\"" + url + "\",\"events\":[\"TASK_CREATED\",\"TASK_DONE\",\"COMMENT_ADDED\"]}")
                .andExpect(status().isCreated()));
        assertThat(hook.get("url").asText()).doesNotContain("/chat");

        chatBodies.clear();
        JsonNode task = task(member, key, "Ship *it* @everyone");
        perform(patch("/api/tasks/" + task.get("id").asLong() + "/status"), member, body("status", "DONE"))
                .andExpect(status().isOk());
        perform(post("/api/tasks/" + task.get("id").asLong() + "/comments"), member, body("body", "Done & dusted"))
                .andExpect(status().isCreated());
        for (int i = 0; i < 100 && chatBodies.size() < 3; i++) {
            Thread.sleep(50);
        }
        String all = String.join("\n", chatBodies);
        assertThat(all).contains("created").contains("completed").contains("commented on")
                .contains("https://jira.example.test/tasks/" + task.get("id").asLong())
                .contains("\\\\*it\\\\*") // Discord markdown in titles is escaped
                .contains("\"allowed_mentions\":{\"parse\":[]}");
        // Status changes other than Done were not subscribed.
        assertThat(all).doesNotContain("moved");

        perform(post("/api/chat-hooks/" + hook.get("id").asLong() + "/test"), owner)
                .andExpect(jsonPath("$.delivered").value(true));
    }

    @Test
    void webhookUrlsMustBePublicHttps() {
        ChatSender strict = new ChatSender(null, null, null, json, false);
        assertThatThrownBy(() -> strict.checkUrl("http://hooks.slack.com/x")).hasMessageContaining("https");
        assertThatThrownBy(() -> strict.checkUrl("https://127.0.0.1/x")).hasMessageContaining("private");
        assertThatThrownBy(() -> strict.checkUrl("https://10.1.2.3/x")).hasMessageContaining("private");
        assertThatThrownBy(() -> strict.checkUrl("https://169.254.169.254/latest")).hasMessageContaining("private");
        assertThatThrownBy(() -> strict.checkUrl("https://100.101.1.2/x")).hasMessageContaining("private");
    }

    /** Runs the browser part of the flow: returns the fragment the app is sent to. */
    private String signInWithGithub(String token, String code) throws Exception {
        var start = mvc.perform(post("/api/auth/oauth/github/url").contentType(MediaType.APPLICATION_JSON).content("{}")
                .headers(token == null ? new org.springframework.http.HttpHeaders() : bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse();
        String authorize = json.readTree(start.getContentAsString()).get("url").asText();
        assertThat(authorize).contains("client_id=cid")
                .contains("redirect_uri=" + java.net.URLEncoder.encode("https://jira.example.test/api/auth/oauth/github/callback", StandardCharsets.UTF_8));
        String state = URLDecoder.decode(authorize.replaceAll(".*[?&]state=([^&]+).*", "$1"), StandardCharsets.UTF_8);
        Cookie nonce = start.getCookie("fj_oauth");
        assertThat(nonce).isNotNull();
        assertThat(nonce.isHttpOnly()).isTrue();

        // Without the cookie (another browser) the callback is refused.
        MockHttpServletResponse noCookie = mvc.perform(get("/api/auth/oauth/github/callback")
                .param("code", code).param("state", state)).andReturn().getResponse();
        assertThat(noCookie.getRedirectedUrl()).startsWith("/oauth-complete#error=");

        MockHttpServletResponse callback = mvc.perform(get("/api/auth/oauth/github/callback")
                        .param("code", code).param("state", state).cookie(nonce))
                .andExpect(status().isFound()).andReturn().getResponse();
        return URI.create("http://x" + callback.getRedirectedUrl()).getFragment();
    }

    private org.springframework.http.HttpHeaders bearer(String token) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    void signInWithGithubCreatesLinksAndReusesAccounts() throws Exception {
        mvc.perform(get("/api/auth/providers")).andExpect(jsonPath("$[0].id").value("github"));

        githubUserId = String.valueOf(System.nanoTime() % 1_000_000_000L);
        githubEmail = uniqueName("octo") + "@example.com";
        String fragment = signInWithGithub(null, "good");
        assertThat(fragment).startsWith("token=");
        String token = fragment.substring("token=".length());
        JsonNode me = read(mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
        assertThat(me.get("user").get("displayName").asText()).isEqualTo("Octo Cat");
        long userId = me.get("user").get("id").asLong();
        JsonNode profile = read(mvc.perform(get("/api/profile").header("Authorization", "Bearer " + token)));
        assertThat(profile.get("passwordSet").asBoolean()).isFalse();
        assertThat(profile.get("identities").toString()).contains("github");

        // Signing in again finds the same account.
        String again = signInWithGithub(null, "good");
        JsonNode me2 = read(mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + again.substring(6))));
        assertThat(me2.get("user").get("id").asLong()).isEqualTo(userId);

        // A failed code exchange is reported, not a crash.
        assertThat(signInWithGithub(null, "bad")).startsWith("error=");

        // A signed-in password user can connect GitHub; later GitHub sign-in reaches that account.
        Account account = register();
        githubUserId = String.valueOf(System.nanoTime() % 1_000_000_000L + 7);
        githubEmail = uniqueName("other") + "@example.com";
        assertThat(signInWithGithub(account.token(), "good")).isEqualTo("linked=github");
        String viaGithub = signInWithGithub(null, "good");
        JsonNode linked = read(mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + viaGithub.substring(6))));
        assertThat(linked.get("user").get("id").asLong()).isEqualTo(account.id());

        // Only way to sign in cannot be removed.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/profile/identities/github")
                .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/profile/identities/github"),
                account).andExpect(status().isNoContent());
    }
}
