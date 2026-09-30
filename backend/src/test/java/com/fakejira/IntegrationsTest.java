package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CI status, GitHub branches/PRs/issue sync, chat slash commands and unfurls, Google Calendar two-way sync,
 * REST hooks, link previews and the OpenAPI description, against a local fake of the outside services.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrationsTest extends ApiTestSupport {

    record Call(String method, String path, String body, Map<String, String> headers) {
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    static final Map<String, Function<Call, Object[]>> ROUTES = new ConcurrentHashMap<>();
    static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/", IntegrationsTest::serve);
        SERVER.start();
    }

    static String base() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("app.github.api-url", () -> base() + "/github");
        registry.add("app.slack.api-url", () -> base() + "/slack");
        registry.add("app.google-calendar.client-id", () -> "cal-client");
        registry.add("app.google-calendar.client-secret", () -> "cal-secret");
        registry.add("app.google-calendar.auth-url", () -> base() + "/google/auth");
        registry.add("app.google-calendar.token-url", () -> base() + "/google/token");
        registry.add("app.google-calendar.api-url", () -> base() + "/google/calendar/v3");
        registry.add("app.chat.allow-private-addresses", () -> "true");
        registry.add("app.webhooks.retry-delay-ms", () -> "10");
    }

    /** Routes are matched by "METHOD /path" prefix; the handler returns {status, body}. */
    static void serve(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        String path = exchange.getRequestURI().toString();
        Call call = new Call(exchange.getRequestMethod(), path, body, headers);
        CALLS.add(call);
        Object[] response = ROUTES.entrySet().stream()
                .filter(e -> (call.method() + " " + path).startsWith(e.getKey()))
                .max(java.util.Comparator.comparingInt(e -> e.getKey().length()))
                .map(e -> e.getValue().apply(call)).orElse(new Object[]{404, Map.of("message", "Not Found")});
        byte[] bytes = response[1] instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : JSON.writeValueAsBytes(response[1]);
        exchange.getResponseHeaders().add("Content-Type", response.length > 2 ? (String) response[2] : "application/json");
        exchange.sendResponseHeaders((Integer) response[0], bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    static List<Call> calls(String prefix) {
        return CALLS.stream().filter(c -> (c.method() + " " + c.path()).startsWith(prefix)).toList();
    }

    static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private String j(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private String gitSecret(Account owner, String key) throws Exception {
        return read(perform(post("/api/projects/" + key + "/github"), owner).andExpect(status().isOk())).get("secret").asText();
    }

    private static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- CI status ----------------------------------------------------------------------------------------------------

    @Test
    void ciStatusFromGithubGitlabAndGenericCi() throws Exception {
        Account alice = register();
        String key = project(alice);
        JsonNode task = task(alice, key, "Login page");
        long id = task.get("id").asLong();
        String secret = gitSecret(alice, key);

        mvc.perform(post("/api/integrations/ci/" + key).header("X-FakeJIRA-Token", "wrong").contentType(MediaType.APPLICATION_JSON)
                .content(j(Map.of("name", "tests", "status", "passed", "ref", "feature/" + task.get("key").asText())))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/integrations/ci/" + key).header("X-FakeJIRA-Token", secret).contentType(MediaType.APPLICATION_JSON)
                .content(j(Map.of("name", "tests", "status", "passed", "url", "https://ci.example/1",
                        "ref", "feature/" + task.get("key").asText() + "-login")))).andExpect(status().isOk());

        String checkRun = j(Map.of("check_run", Map.of("name", "build", "status", "completed", "conclusion", "failure",
                "html_url", "https://github.com/acme/web/runs/9", "check_suite", Map.of("head_branch", task.get("key").asText() + "-login"))));
        mvc.perform(post("/api/integrations/github/" + key).header("X-GitHub-Event", "check_run")
                .header("X-Hub-Signature-256", "sha256=" + hmac(secret, checkRun)).contentType(MediaType.APPLICATION_JSON).content(checkRun))
                .andExpect(status().isOk());

        String pipeline = j(Map.of("object_attributes", Map.of("id", 5, "status", "running", "ref", "main", "url", "https://gitlab.example/p/5"),
                "commit", Map.of("message", "Fix " + task.get("key").asText())));
        mvc.perform(post("/api/integrations/gitlab/" + key).header("X-Gitlab-Event", "Pipeline Hook").header("X-Gitlab-Token", secret)
                .contentType(MediaType.APPLICATION_JSON).content(pipeline)).andExpect(status().isOk());

        JsonNode builds = getJson("/api/tasks/" + id + "/github", alice).get("builds");
        Map<String, String> states = new LinkedHashMap<>();
        builds.forEach(b -> states.put(b.get("source").asText() + ":" + b.get("name").asText(), b.get("state").asText()));
        assertThat(states).containsEntry("CI:tests", "success").containsEntry("GitHub:build", "failure")
                .containsEntry("GitLab:pipeline", "running");
        assertThat(getJson("/api/projects/" + key + "/builds", alice).get(String.valueOf(id))).isNotNull();
    }

    // ---- GitHub: branch, pull request, issues -----------------------------------------------------------------------

    @Test
    void githubBranchPullRequestAndIssueSync() throws Exception {
        Account alice = register();
        String key = project(alice);
        String secret = gitSecret(alice, key);
        ROUTES.put("GET /github/repos/acme/web", c -> new Object[]{200, Map.of("full_name", "acme/web", "default_branch", "main")});
        ROUTES.put("GET /github/repos/acme/web/git/ref/heads/main", c -> new Object[]{200, Map.of("object", Map.of("sha", "abc123"))});
        ROUTES.put("POST /github/repos/acme/web/git/refs", c -> new Object[]{201, Map.of("ref", "refs/heads/x")});
        ROUTES.put("POST /github/repos/acme/web/pulls", c -> new Object[]{201, Map.of("number", 7, "html_url", "https://github.com/acme/web/pull/7")});
        ROUTES.put("GET /github/repos/acme/web/issues", c -> new Object[]{200, List.of(
                Map.of("number", 3, "title", "Crash on save", "body", "Stack trace…", "state", "open", "html_url", "https://github.com/acme/web/issues/3",
                        "labels", List.of(Map.of("name", "bug"))),
                Map.of("number", 4, "title", "Old idea", "body", "", "state", "closed", "html_url", "https://github.com/acme/web/issues/4", "labels", List.of()),
                Map.of("number", 5, "title", "A pull request", "pull_request", Map.of(), "state", "open", "html_url", "x", "labels", List.of()))});
        ROUTES.put("PATCH /github/repos/acme/web/issues/", c -> new Object[]{200, Map.of()});

        perform(put("/api/projects/" + key + "/github/repo"), alice, j(Map.of("repo", "not a repo"))).andExpect(status().isBadRequest());
        perform(put("/api/projects/" + key + "/github/repo"), alice, j(Map.of("repo", "acme/web", "issueSync", true)))
                .andExpect(status().isBadRequest());
        JsonNode repo = read(perform(put("/api/projects/" + key + "/github/repo"), alice,
                j(Map.of("repo", "acme/web", "token", "ghp_test", "issueSync", true))).andExpect(status().isOk()));
        assertThat(repo.get("hasToken").asBoolean()).isTrue();
        assertThat(repo.toString()).doesNotContain("ghp_test");
        assertThat(calls("GET /github/repos/acme/web").get(0).headers().get("authorization")).isEqualTo("Bearer ghp_test");

        JsonNode task = task(alice, key, "Löse the Login bug!");
        long id = task.get("id").asLong();
        JsonNode branch = read(perform(post("/api/tasks/" + id + "/github/branch"), alice, "{}").andExpect(status().isCreated()));
        assertThat(branch.get("branch").asText()).isEqualTo(task.get("key").asText() + "-lose-the-login-bug");
        assertThat(calls("POST /github/repos/acme/web/git/refs").get(0).body()).contains("\"sha\":\"abc123\"")
                .contains("refs/heads/" + branch.get("branch").asText());
        JsonNode pr = read(perform(post("/api/tasks/" + id + "/github/pull-request"), alice, j(Map.of("draft", true)))
                .andExpect(status().isCreated()));
        assertThat(pr.get("number").asInt()).isEqualTo(7);
        String prBody = calls("POST /github/repos/acme/web/pulls").get(0).body();
        assertThat(prBody).contains("\"head\":\"" + branch.get("branch").asText() + "\"").contains("\"draft\":true")
                .contains("Resolves " + task.get("key").asText());
        assertThat(getJson("/api/tasks/" + id + "/dev", alice).toString()).contains("BRANCH").contains("PULL_REQUEST");

        // Import: two issues (one closed), the pull request is skipped.
        JsonNode imported = read(perform(post("/api/projects/" + key + "/github/import"), alice, j(Map.of("state", "all")))
                .andExpect(status().isOk()));
        assertThat(imported.get("imported").asInt()).isEqualTo(2);
        assertThat(imported.get("pullRequestsSkipped").asInt()).isEqualTo(1);
        JsonNode crash = getJson("/api/tasks?project=" + key + "&q=Crash", alice).get(0);
        assertThat(crash.get("type").asText()).isEqualTo("BUG");
        assertThat(crash.get("labels").toString()).contains("github");
        assertThat(getJson("/api/tasks/" + crash.get("id").asLong() + "/github", alice).get("issueNumber").asInt()).isEqualTo(3);
        JsonNode old = getJson("/api/tasks?project=" + key + "&q=Old idea", alice).get(0);
        assertThat(old.get("status").asText()).isEqualTo("DONE");
        assertThat(read(perform(post("/api/projects/" + key + "/github/import"), alice, "{}")).get("imported").asInt()).isZero();
        assertThat(calls("PATCH /github/repos/acme/web/issues/")).isEmpty();

        // Closing the task closes the issue.
        perform(patch("/api/tasks/" + crash.get("id").asLong() + "/status"), alice, j(Map.of("status", "DONE", "resolution", "FIXED")))
                .andExpect(status().isOk());
        waitFor(() -> !calls("PATCH /github/repos/acme/web/issues/3").isEmpty());
        assertThat(calls("PATCH /github/repos/acme/web/issues/3").get(0).body()).contains("\"state\":\"closed\"").contains("completed");

        // Reopening the issue on GitHub reopens the task, without echoing back.
        int patches = calls("PATCH /github").size();
        String reopened = j(Map.of("action", "reopened", "repository", Map.of("full_name", "acme/web"), "issue", Map.of("number", 3, "title", "Crash on save")));
        mvc.perform(post("/api/integrations/github/" + key).header("X-GitHub-Event", "issues")
                .header("X-Hub-Signature-256", "sha256=" + hmac(secret, reopened)).contentType(MediaType.APPLICATION_JSON).content(reopened))
                .andExpect(status().isOk());
        assertThat(getJson("/api/tasks/" + crash.get("id").asLong(), alice).get("status").asText()).isEqualTo("TODO");
        Thread.sleep(300);
        assertThat(calls("PATCH /github")).hasSize(patches);

        // A new issue on GitHub becomes a task.
        String opened = j(Map.of("action", "opened", "repository", Map.of("full_name", "acme/web"), "issue", Map.of("number", 9,
                "title", "Brand new", "body", "", "state", "open", "html_url", "https://github.com/acme/web/issues/9", "labels", List.of())));
        mvc.perform(post("/api/integrations/github/" + key).header("X-GitHub-Event", "issues")
                .header("X-Hub-Signature-256", "sha256=" + hmac(secret, opened)).contentType(MediaType.APPLICATION_JSON).content(opened))
                .andExpect(status().isOk());
        assertThat(getJson("/api/tasks?project=" + key + "&q=Brand new", alice)).hasSize(1);
    }

    // ---- Chat commands --------------------------------------------------------------------------------------------------

    @Test
    void slackMattermostAndDiscordCommands() throws Exception {
        Account alice = register();
        String key = project(alice);
        JsonNode task = task(alice, key, "Checkout is slow");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair discordKeys = generator.generateKeyPair();
        byte[] x509 = discordKeys.getPublic().getEncoded();
        String discordPublic = HexFormat.of().formatHex(Arrays.copyOfRange(x509, x509.length - 32, x509.length));
        JsonNode settings = read(perform(put("/api/projects/" + key + "/chat-commands"), alice, j(Map.of(
                "slackSigningSecret", "slack-secret", "slackBotToken", "xoxb-1", "mattermostToken", "mm-token",
                "discordPublicKey", discordPublic))).andExpect(status().isOk()));
        assertThat(settings.get("slackSigningSecret").asBoolean()).isTrue();
        assertThat(settings.toString()).doesNotContain("slack-secret");

        // Slack: signed form posts.
        String form = "command=%2Ffj&user_name=dana&text=" + task.get("key").asText();
        String ts = String.valueOf(Instant.now().getEpochSecond());
        JsonNode reply = read(mvc.perform(post("/api/integrations/slack/" + key + "/command").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Request-Timestamp", ts).header("X-Slack-Signature", "v0=" + hmac("slack-secret", "v0:" + ts + ":" + form))
                .content(form)).andExpect(status().isOk()));
        assertThat(reply.get("text").asText()).contains("Checkout is slow").contains("To do");
        mvc.perform(post("/api/integrations/slack/" + key + "/command").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Request-Timestamp", ts).header("X-Slack-Signature", "v0=bad").content(form)).andExpect(status().isUnauthorized());
        String old = String.valueOf(Instant.now().getEpochSecond() - 3600);
        mvc.perform(post("/api/integrations/slack/" + key + "/command").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Request-Timestamp", old).header("X-Slack-Signature", "v0=" + hmac("slack-secret", "v0:" + old + ":" + form))
                .content(form)).andExpect(status().isUnauthorized());
        String create = "user_name=dana&text=create+Add+dark+mode";
        JsonNode created = read(mvc.perform(post("/api/integrations/slack/" + key + "/command").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Request-Timestamp", ts).header("X-Slack-Signature", "v0=" + hmac("slack-secret", "v0:" + ts + ":" + create))
                .content(create)).andExpect(status().isOk()));
        assertThat(created.get("response_type").asText()).isEqualTo("in_channel");
        JsonNode newTask = getJson("/api/tasks?project=" + key + "&q=dark mode", alice).get(0);
        assertThat(newTask.get("description").asText()).contains("Slack").contains("@dana");

        // Slack events: URL check and unfurling task links.
        String challenge = j(Map.of("type", "url_verification", "challenge", "abc"));
        assertThat(read(mvc.perform(post("/api/integrations/slack/" + key + "/events").contentType(MediaType.APPLICATION_JSON)
                .header("X-Slack-Request-Timestamp", ts).header("X-Slack-Signature", "v0=" + hmac("slack-secret", "v0:" + ts + ":" + challenge))
                .content(challenge))).get("challenge").asText()).isEqualTo("abc");
        ROUTES.put("POST /slack/chat.unfurl", c -> new Object[]{200, Map.of("ok", true)});
        String site = settings.get("slackCommandUrl").asText().replaceAll("/api/.*", "");
        String link = site + "/tasks/" + task.get("id").asLong();
        String shared = j(Map.of("type", "event_callback", "event", Map.of("type", "link_shared", "channel", "C1", "message_ts", "1.2",
                "links", List.of(Map.of("url", link, "domain", "localhost")))));
        mvc.perform(post("/api/integrations/slack/" + key + "/events").contentType(MediaType.APPLICATION_JSON)
                .header("X-Slack-Request-Timestamp", ts).header("X-Slack-Signature", "v0=" + hmac("slack-secret", "v0:" + ts + ":" + shared))
                .content(shared)).andExpect(status().isOk());
        List<Call> unfurls = calls("POST /slack/chat.unfurl");
        assertThat(unfurls).hasSize(1);
        assertThat(unfurls.get(0).headers().get("authorization")).isEqualTo("Bearer xoxb-1");
        assertThat(unfurls.get(0).body()).contains("Checkout is slow").contains(link);

        // Mattermost: token in the form.
        mvc.perform(post("/api/integrations/mattermost/" + key + "/command").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("token=wrong&text=help")).andExpect(status().isUnauthorized());
        JsonNode help = read(mvc.perform(post("/api/integrations/mattermost/" + key + "/command").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("token=mm-token&text=search+checkout&user_name=eve")).andExpect(status().isOk()));
        assertThat(help.get("text").asText()).contains("[" + task.get("key").asText() + " Checkout is slow](");

        // Discord: Ed25519-signed interactions.
        String ping = "{\"type\":1}";
        mvc.perform(post("/api/integrations/discord/" + key + "/interactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature-Timestamp", ts).header("X-Signature-Ed25519", sign(discordKeys, ts + ping)).content(ping))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.type").value(1));
        mvc.perform(post("/api/integrations/discord/" + key + "/interactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature-Timestamp", ts).header("X-Signature-Ed25519", "00".repeat(64)).content(ping))
                .andExpect(status().isUnauthorized());
        String command = j(Map.of("type", 2, "data", Map.of("name", "fakejira", "options", List.of(Map.of("name", "text", "value", "help"))),
                "member", Map.of("user", Map.of("username", "zed"))));
        JsonNode discord = read(mvc.perform(post("/api/integrations/discord/" + key + "/interactions").contentType(MediaType.APPLICATION_JSON)
                .header("X-Signature-Timestamp", ts).header("X-Signature-Ed25519", sign(discordKeys, ts + command)).content(command))
                .andExpect(status().isOk()));
        assertThat(discord.get("type").asInt()).isEqualTo(4);
        assertThat(discord.get("data").get("flags").asInt()).isEqualTo(64);
        assertThat(discord.get("data").get("content").asText()).contains("Commands for");
    }

    private static String sign(KeyPair keys, String data) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(data.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(signer.sign());
    }

    // ---- Google Calendar ------------------------------------------------------------------------------------------------

    @Test
    void googleCalendarTwoWaySync() throws Exception {
        Map<String, Map<String, Object>> events = new ConcurrentHashMap<>();
        AtomicInteger ids = new AtomicInteger();
        ROUTES.put("POST /google/token", c -> {
            Map<String, String> form = new LinkedHashMap<>();
            for (String pair : c.body().split("&")) {
                String[] kv = pair.split("=", 2);
                form.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            if (!"cal-secret".equals(form.get("client_secret"))) return new Object[]{401, Map.of("error", "invalid_client")};
            return "authorization_code".equals(form.get("grant_type"))
                    ? new Object[]{200, Map.of("access_token", "at-1", "refresh_token", "rt-1", "expires_in", 3600)}
                    : new Object[]{200, Map.of("access_token", "at-2", "expires_in", 3600)};
        });
        ROUTES.put("POST /google/calendar/v3/calendars/primary/events", c -> {
            Map<String, Object> event = readMap(c.body());
            String id = "ev" + ids.incrementAndGet();
            event.put("id", id);
            event.put("status", "confirmed");
            event.put("updated", Instant.now().toString());
            events.put(id, event);
            return new Object[]{200, event};
        });
        ROUTES.put("PATCH /google/calendar/v3/calendars/primary/events/", c -> {
            String id = c.path().substring(c.path().lastIndexOf('/') + 1);
            events.get(id).putAll(readMap(c.body()));
            return new Object[]{200, events.get(id)};
        });
        ROUTES.put("DELETE /google/calendar/v3/calendars/primary/events/", c -> {
            events.remove(c.path().substring(c.path().lastIndexOf('/') + 1));
            return new Object[]{204, ""};
        });
        ROUTES.put("GET /google/calendar/v3/calendars/primary/events", c -> new Object[]{200, Map.of("items", new ArrayList<>(events.values()))});

        Account alice = register();
        String key = project(alice);
        assertThat(getJson("/api/integrations/google-calendar", alice).get("available").asBoolean()).isTrue();
        String url = read(perform(post("/api/integrations/google-calendar/connect"), alice)).get("url").asText();
        assertThat(url).startsWith(base() + "/google/auth?").contains("calendar.events").contains("access_type=offline");
        String state = URLDecoder.decode(url.replaceAll(".*[?&]state=([^&]+).*", "$1"), StandardCharsets.UTF_8);
        mvc.perform(get("/api/integrations/google-calendar/callback?code=abc&state=bogus")).andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "/profile?calendar=expired#calendar"));
        mvc.perform(get("/api/integrations/google-calendar/callback?code=abc&state=" + state)).andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "/profile?calendar=connected#calendar"));
        assertThat(getJson("/api/integrations/google-calendar", alice).get("connected").asBoolean()).isTrue();

        LocalDate due = LocalDate.now().plusDays(5);
        long id = task(alice, key, "Ship release", "assigneeId", alice.id(), "dueDate", due.toString()).get("id").asLong();
        task(alice, key, "No date", "assigneeId", alice.id());
        JsonNode result = read(perform(post("/api/integrations/google-calendar/sync"), alice).andExpect(status().isOk()));
        assertThat(result.get("created").asInt()).isEqualTo(1);
        Map<String, Object> event = events.values().iterator().next();
        assertThat(((Map<?, ?>) event.get("start")).get("date")).isEqualTo(due.toString());
        assertThat(event.get("summary").toString()).endsWith("Ship release");

        // Moving it in Google Calendar moves the task.
        LocalDate moved = due.plusDays(2);
        event.put("start", Map.of("date", moved.toString()));
        event.put("end", Map.of("date", moved.plusDays(1).toString()));
        result = read(perform(post("/api/integrations/google-calendar/sync"), alice).andExpect(status().isOk()));
        assertThat(result.get("pulled").asInt()).isEqualTo(1);
        assertThat(getJson("/api/tasks/" + id, alice).get("dueDate").asText()).isEqualTo(moved.toString());
        assertThat(result.get("updated").asInt()).isZero();

        // Changing it in FakeJIRA updates the event; finishing the task removes it.
        perform(put("/api/tasks/" + id), alice, j(Map.of("title", "Ship release 2", "description", "", "priority", "MEDIUM",
                "dueDate", due.toString(), "labels", List.of()))).andExpect(status().isOk());
        result = read(perform(post("/api/integrations/google-calendar/sync"), alice).andExpect(status().isOk()));
        assertThat(result.get("updated").asInt()).isEqualTo(1);
        assertThat(events.values().iterator().next().get("summary").toString()).endsWith("Ship release 2");
        perform(patch("/api/tasks/" + id + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        result = read(perform(post("/api/integrations/google-calendar/sync"), alice).andExpect(status().isOk()));
        assertThat(result.get("removed").asInt()).isEqualTo(1);
        assertThat(events).isEmpty();

        perform(delete("/api/integrations/google-calendar"), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/integrations/google-calendar", alice).get("connected").asBoolean()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(String body) {
        try {
            return new LinkedHashMap<>(JSON.readValue(body, Map.class));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- REST hooks, link previews, OpenAPI -----------------------------------------------------------------------------

    @Test
    void restHooksForAutomationPlatforms() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        ROUTES.put("POST /zap/", c -> new Object[]{200, Map.of()});
        perform(post("/api/rest-hooks"), bob, j(Map.of("projectKey", key, "event", "nope", "targetUrl", base() + "/zap/1")))
                .andExpect(status().isBadRequest());
        JsonNode hook = read(perform(post("/api/rest-hooks"), bob, j(Map.of("projectKey", key, "event", "task.created",
                "targetUrl", base() + "/zap/1"))).andExpect(status().isCreated()));
        task(alice, key, "From Zapier's point of view");
        waitFor(() -> !calls("POST /zap/1").isEmpty());
        Call delivery = calls("POST /zap/1").get(0);
        assertThat(delivery.headers().get("x-fakejira-event")).isEqualTo("task.created");
        assertThat(delivery.body()).contains("From Zapier's point of view");

        JsonNode sample = getJson("/api/rest-hooks/sample?project=" + key + "&event=task.created", bob);
        assertThat(sample.get(0).toString()).contains("From Zapier's point of view");
        assertThat(getJson("/api/projects/" + key + "/tasks/recent?limit=5", bob).get(0).get("title").asText())
                .isEqualTo("From Zapier's point of view");

        Account carol = register();
        perform(delete("/api/rest-hooks/" + hook.get("id").asLong()), carol).andExpect(status().isNotFound());
        perform(delete("/api/rest-hooks/" + hook.get("id").asLong()), bob).andExpect(status().isNoContent());
    }

    @Test
    void linkPreviewsAndEmbeds() throws Exception {
        Account alice = register();
        ROUTES.put("GET /page", c -> new Object[]{200, """
                <html><head><title>Fallback</title>
                <meta property="og:title" content="Design &amp; review">
                <meta name="description" content="The new checkout">
                <meta property="og:image" content="https://cdn.example/img.png">
                </head></html>""", "text/html; charset=utf-8"});
        ROUTES.put("GET /moved", c -> new Object[]{302, "", "text/plain"});
        JsonNode preview = getJson("/api/link-preview?url=" + base() + "/page", alice);
        assertThat(preview.get("title").asText()).isEqualTo("Design & review");
        assertThat(preview.get("description").asText()).isEqualTo("The new checkout");
        assertThat(preview.get("image").asText()).isEqualTo("https://cdn.example/img.png");
        assertThat(preview.get("kind").asText()).isEqualTo("link");
        JsonNode missing = getJson("/api/link-preview?url=" + base() + "/nothing-here", alice);
        assertThat(missing.get("title").isNull()).isTrue();

        assertThat(com.fakejira.integration.LinkPreviewAccess.embed("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"))
                .containsExactly("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", "youtube");
        assertThat(com.fakejira.integration.LinkPreviewAccess.embed("https://www.figma.com/design/AbC123/Checkout")[1]).isEqualTo("figma");
        assertThat(com.fakejira.integration.LinkPreviewAccess.embed("https://docs.google.com/document/d/1xYz/edit")[0])
                .isEqualTo("https://docs.google.com/document/d/1xYz/preview");
        assertThat(com.fakejira.integration.LinkPreviewAccess.embed("https://example.com")).isNull();
        perform(get("/api/link-preview?url=file:///etc/passwd"), alice).andExpect(status().isBadRequest());
    }

    @Test
    void openApiDescription() throws Exception {
        JsonNode spec = read(mvc.perform(get("/v3/api-docs/fakejira")).andExpect(status().isOk()));
        assertThat(spec.get("info").get("title").asText()).isEqualTo("FakeJIRA API");
        assertThat(spec.get("paths").has("/api/tasks/{id}")).isTrue();
        assertThat(spec.get("paths").has("/api/rest-hooks")).isTrue();
        assertThat(spec.get("components").get("securitySchemes").has("bearer")).isTrue();
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
