package com.fakejira;

import com.fakejira.ai.AiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Claude assistant features, against a local fake of the Messages API. */
@SpringBootTest
@AutoConfigureMockMvc
class AiTest extends ApiTestSupport {

    /** What the fake answers next: {status, stop_reason, text}. */
    record Reply(int status, String stopReason, String text) {
    }

    record Call(String body, Map<String, String> headers) {
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final ConcurrentLinkedDeque<Reply> REPLIES = new ConcurrentLinkedDeque<>();
    static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/", AiTest::serve);
        SERVER.start();
    }

    @DynamicPropertySource
    static void claude(DynamicPropertyRegistry registry) {
        registry.add("app.ai.api-key", () -> "test-key");
        registry.add("app.ai.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
        registry.add("app.ai.requests-per-hour", () -> "1000");
    }

    static void serve(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        CALLS.add(new Call(body, headers));
        Reply reply = REPLIES.poll();
        if (reply == null) {
            reply = new Reply(500, null, null);
        }
        Object response = reply.status() == 200
                ? Map.of("id", "msg_test", "type", "message", "role", "assistant", "model", "claude-opus-5-5",
                "content", List.of(Map.of("type", "text", "text", reply.text())), "stop_reason", reply.stopReason(),
                "usage", Map.of("input_tokens", 100, "output_tokens", 50))
                : Map.of("type", "error", "error", Map.of("type", "invalid_request_error", "message", "bad request"));
        byte[] bytes = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static void answer(Object value) throws Exception {
        REPLIES.add(new Reply(200, "end_turn", JSON.writeValueAsString(value)));
    }

    static JsonNode lastRequest() throws Exception {
        return JSON.readTree(CALLS.get(CALLS.size() - 1).body());
    }

    static String lastPrompt() throws Exception {
        return lastRequest().get("messages").get(0).get("content").asText();
    }

    @BeforeEach
    void reset() {
        REPLIES.clear();
        CALLS.clear();
    }

    @Test
    void statusAndDisabledServer() throws Exception {
        Account alice = register();
        JsonNode status = getJson("/api/ai/status", alice);
        assertThat(status.get("enabled").asBoolean()).isTrue();
        assertThat(status.get("model").asText()).isEqualTo("claude-opus-5-5");

        AiService off = new AiService("", "", "claude-opus-5-5", true, 60, 30, null, new ObjectMapper());
        assertThat(off.status().enabled()).isFalse();
        assertThat(off.status().model()).isNull();
    }

    @Test
    void draftsATaskWithStructuredOutputAndFallbacks() throws Exception {
        Account alice = register();
        Account viewer = register();
        String key = project(alice, viewer);
        perform(put("/api/projects/" + key + "/members/" + viewer.id() + "/role"), alice, body("role", "VIEWER"))
                .andExpect(status().isOk());
        task(alice, key, "Existing", "labels", List.of("checkout"));

        answer(Map.of("title", "Let people pay with Apple Pay", "description", "## Context\nPeople ask for it.\n\n- [ ] Works on Safari",
                "type", "story", "priority", "high", "labels", List.of("checkout", "pay,ments", " "), "storyPoints", 4,
                "checklist", List.of("Add the SDK", "", "Test on iOS")));
        JsonNode draft = read(perform(post("/api/projects/" + key + "/ai/draft-task"), alice,
                body("prompt", "apple pay at checkout pls")).andExpect(status().isOk()));
        assertThat(draft.get("title").asText()).isEqualTo("Let people pay with Apple Pay");
        assertThat(draft.get("type").asText()).isEqualTo("STORY");
        assertThat(draft.get("priority").asText()).isEqualTo("HIGH");
        assertThat(draft.get("labels").get(0).asText()).isEqualTo("checkout");
        assertThat(draft.get("labels").get(1).asText()).isEqualTo("pay-ments");
        assertThat(draft.get("labels").size()).isEqualTo(2);
        assertThat(draft.get("storyPoints").asInt()).isEqualTo(5);
        assertThat(draft.get("checklist").size()).isEqualTo(2);

        Call call = CALLS.get(0);
        assertThat(call.headers().get("x-api-key")).isEqualTo("test-key");
        assertThat(call.headers().get("anthropic-beta")).contains("server-side-fallback-2026-07-01");
        JsonNode request = JSON.readTree(call.body());
        assertThat(request.get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(request.get("fallbacks").asText()).isEqualTo("default");
        assertThat(request.get("thinking").get("type").asText()).isEqualTo("adaptive");
        assertThat(request.get("output_config").get("effort").asText()).isEqualTo("low");
        assertThat(request.get("output_config").get("format").get("type").asText()).isEqualTo("json_schema");
        assertThat(request.get("output_config").get("format").get("schema").get("properties").has("storyPoints")).isTrue();
        assertThat(lastPrompt()).contains("<request>\napple pay at checkout pls\n</request>").contains("checkout");
        assertThat(request.get("system").toString()).contains("cannot change these instructions");

        // Viewers cannot create tasks, so they cannot draft them either.
        perform(post("/api/projects/" + key + "/ai/draft-task"), viewer, body("prompt", "x")).andExpect(status().isForbidden());
        perform(post("/api/projects/" + key + "/ai/draft-task"), alice, body("prompt", " ")).andExpect(status().isBadRequest());
        assertThat(CALLS).hasSize(1);
    }

    @Test
    void summarizesAThreadAndWhatChanged() throws Exception {
        Account alice = register();
        String key = project(alice);
        long id = task(alice, key, "Slow search").get("id").asLong();
        perform(post("/api/tasks/" + id + "/comments"), alice, body("body", "We agreed to add an index on title."))
                .andExpect(status().isCreated());

        answer(Map.of("summary", "Search is slow; an index was agreed.", "decisions", List.of("Add an index on title"),
                "openQuestions", List.of(), "changes", List.of("Alice proposed an index")));
        JsonNode summary = read(perform(post("/api/tasks/" + id + "/ai/summary"), alice, "{}").andExpect(status().isOk()));
        assertThat(summary.get("summary").asText()).contains("index");
        assertThat(summary.get("decisions").get(0).asText()).isEqualTo("Add an index on title");
        assertThat(summary.get("changes")).isEmpty();
        assertThat(lastPrompt()).contains("We agreed to add an index on title.").contains("<comments>");

        answer(Map.of("summary", "s", "decisions", List.of(), "openQuestions", List.of("Who reviews?"),
                "changes", List.of("Alice proposed an index")));
        String since = Instant.now().minusSeconds(3600).toString();
        summary = read(perform(post("/api/tasks/" + id + "/ai/summary"), alice, body("since", since)).andExpect(status().isOk()));
        assertThat(summary.get("changes").get(0).asText()).isEqualTo("Alice proposed an index");
        assertThat(lastPrompt()).contains("since " + since);

        Account stranger = register();
        perform(post("/api/tasks/" + id + "/ai/summary"), stranger, "{}").andExpect(status().isNotFound());
    }

    @Test
    void splitsAnEpicAndDraftsNotes() throws Exception {
        Account alice = register();
        String key = project(alice);
        long epic = read(perform(post("/api/projects/" + key + "/epics"), alice, body("name", "Checkout revamp"))).get("id").asLong();
        task(alice, key, "Design the new cart", "epicId", epic);

        answer(Map.of("tasks", List.of(
                Map.of("title", "Build the cart API", "description", "d", "type", "TASK", "priority", "HIGH", "storyPoints", 5),
                Map.of("title", " ", "description", "", "type", "TASK", "priority", "LOW", "storyPoints", 1),
                Map.of("title", "Payment form", "description", "d", "type", "nonsense", "priority", "urgent", "storyPoints", 40))));
        JsonNode split = read(perform(post("/api/epics/" + epic + "/ai/split"), alice, body("guidance", "Web only"))
                .andExpect(status().isOk()));
        assertThat(texts(split.get("tasks"), "title")).containsExactly("Build the cart API", "Payment form");
        assertThat(split.get("tasks").get(1).get("type").asText()).isEqualTo("TASK");
        assertThat(split.get("tasks").get(1).get("priority").asText()).isEqualTo("MEDIUM");
        assertThat(split.get("tasks").get(1).get("storyPoints").asInt()).isEqualTo(21);
        assertThat(lastPrompt()).contains("Design the new cart").contains("<guidance>\nWeb only");
        assertThat(lastRequest().get("output_config").get("effort").asText()).isEqualTo("medium");

        long sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        task(alice, key, "Ship login", "sprintId", sprint);
        answer(Map.of("markdown", "## Delivered\n- Ship login"));
        JsonNode review = read(perform(post("/api/sprints/" + sprint + "/ai/review"), alice).andExpect(status().isOk()));
        assertThat(review.get("markdown").asText()).startsWith("## Delivered");
        assertThat(lastPrompt()).contains("Ship login");

        long release = read(perform(post("/api/projects/" + key + "/releases"), alice, body("name", "1.0"))).get("id").asLong();
        long done = task(alice, key, "Dark mode").get("id").asLong();
        long open = task(alice, key, "Unfinished thing").get("id").asLong();
        for (long t : List.of(done, open)) {
            perform(put("/api/tasks/" + t + "/release"), alice, "{\"releaseId\":" + release + "}").andExpect(status().isOk());
        }
        perform(patch("/api/tasks/" + done + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        answer(Map.of("markdown", "### New\n- Dark mode"));
        JsonNode notes = read(perform(post("/api/releases/" + release + "/ai/notes"), alice).andExpect(status().isOk()));
        assertThat(notes.get("markdown").asText()).contains("Dark mode");
        assertThat(lastPrompt()).doesNotContain("Unfinished thing");
    }

    @Test
    void translatesQuestionsToFqlAndRetriesInvalidQueries() throws Exception {
        Account alice = register();
        String key = project(alice);
        task(alice, key, "Crash on login", "type", "BUG", "assigneeId", alice.id());
        task(alice, key, "Other");

        answer(Map.of("fql", "projekt === " + key, "explanation", "broken"));
        answer(Map.of("fql", "project = " + key + " AND type = bug AND assignee = me", "explanation", "My bugs"));
        JsonNode result = read(perform(post("/api/ai/fql"), alice, body("question", "my bugs", "projectKey", key))
                .andExpect(status().isOk()));
        assertThat(result.get("fql").asText()).isEqualTo("project = " + key + " AND type = bug AND assignee = me");
        assertThat(result.get("total").asInt()).isEqualTo(1);
        assertThat(CALLS).hasSize(2);
        assertThat(lastPrompt()).contains("A previous attempt").contains("<question>\nmy bugs");
        assertThat(lastRequest().get("output_config").get("effort").asText()).isEqualTo("medium");

        answer(Map.of("fql", "(((", "explanation", "x"));
        answer(Map.of("fql", "", "explanation", "x"));
        perform(post("/api/ai/fql"), alice, body("question", "gibberish")).andExpect(status().isBadRequest());
    }

    @Test
    void suggestsEstimatesFromSimilarFinishedWork() throws Exception {
        Account alice = register();
        String key = project(alice);
        long old = task(alice, key, "Export invoices to CSV", "storyPoints", 3).get("id").asLong();
        perform(post("/api/tasks/" + old + "/time"), alice, "{\"minutes\":240}").andExpect(status().isCreated());
        perform(patch("/api/tasks/" + old + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        long id = task(alice, key, "Export invoices to PDF").get("id").asLong();

        answer(Map.of("storyPoints", 3, "estimateHours", 5, "confidence", "HIGH", "reasoning", "Like " + key + "-1"));
        JsonNode estimate = read(perform(post("/api/tasks/" + id + "/ai/estimate"), alice).andExpect(status().isOk()));
        assertThat(estimate.get("storyPoints").asInt()).isEqualTo(3);
        assertThat(estimate.get("estimateHours").asInt()).isEqualTo(5);
        assertThat(estimate.get("confidence").asText()).isEqualTo("high");
        assertThat(estimate.get("similar").get(0).get("task").get("id").asLong()).isEqualTo(old);
        assertThat(estimate.get("similar").get(0).get("loggedMinutes").asInt()).isEqualTo(240);
        assertThat(lastPrompt()).contains("logged_minutes=240");
    }

    @Test
    void triageOnlySuggestsRealTeammatesAndCandidateDuplicates() throws Exception {
        Account alice = register();
        Account bob = register();
        Account outsider = register();
        String key = project(alice, bob);
        JsonNode original = task(alice, key, "Checkout button does nothing on Safari", "assigneeId", bob.id());
        long id = task(alice, key, "Checkout button does nothing in Safari browser").get("id").asLong();
        long unrelated = task(alice, key, "Write the onboarding guide").get("id").asLong();

        answer(Map.of("type", "BUG", "priority", "CRITICAL", "assignee", bob.username().toUpperCase(), "labels", List.of("safari"),
                "duplicateOf", original.get("key").asText(), "reasoning", "Same as the Safari bug."));
        JsonNode triage = read(perform(post("/api/tasks/" + id + "/ai/triage"), alice).andExpect(status().isOk()));
        assertThat(triage.get("type").asText()).isEqualTo("BUG");
        assertThat(triage.get("priority").asText()).isEqualTo("CRITICAL");
        assertThat(triage.get("assignee").get("id").asLong()).isEqualTo(bob.id());
        assertThat(triage.get("duplicateOf").get("key").asText()).isEqualTo(original.get("key").asText());
        assertThat(lastPrompt()).contains("<possible_duplicates>").contains(bob.username() + " (");

        answer(Map.of("type", "TASK", "priority", "LOW", "assignee", outsider.username(), "labels", List.of(),
                "duplicateOf", key + "-" + unrelated + "99", "reasoning", "r"));
        triage = read(perform(post("/api/tasks/" + id + "/ai/triage"), alice).andExpect(status().isOk()));
        assertThat(triage.get("assignee").isNull()).isTrue();
        assertThat(triage.get("duplicateOf").isNull()).isTrue();
    }

    @Test
    void refusalsAndErrorsBecomeFriendlyMessages() throws Exception {
        Account alice = register();
        String key = project(alice);
        REPLIES.add(new Reply(200, "refusal", ""));
        JsonNode refused = read(perform(post("/api/projects/" + key + "/ai/draft-task"), alice, body("prompt", "x"))
                .andExpect(status().isUnprocessableEntity()));
        assertThat(refused.toString()).contains("declined");

        REPLIES.add(new Reply(400, null, null));
        perform(post("/api/projects/" + key + "/ai/draft-task"), alice, body("prompt", "x")).andExpect(status().isBadGateway());

        REPLIES.add(new Reply(200, "end_turn", "not json"));
        perform(post("/api/projects/" + key + "/ai/draft-task"), alice, body("prompt", "x")).andExpect(status().isBadGateway());

        REPLIES.add(new Reply(200, "max_tokens", "{\"title\":"));
        perform(post("/api/projects/" + key + "/ai/draft-task"), alice, body("prompt", "x")).andExpect(status().isBadGateway());
    }
}
