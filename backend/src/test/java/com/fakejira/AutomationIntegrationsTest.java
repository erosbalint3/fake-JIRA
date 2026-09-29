package com.fakejira;

import com.fakejira.automation.AutomationService;
import com.fakejira.webhook.WebhookService;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Automation rules, outgoing webhooks, API tokens, GitLab/Gitea, share links and Jira/Trello import. */
@SpringBootTest(properties = {"app.chat.allow-private-addresses=true", "app.webhooks.retry-delay-ms=10"})
@AutoConfigureMockMvc
class AutomationIntegrationsTest extends ApiTestSupport {

    @Autowired
    private AutomationService automation;

    private String rule(String name, String trigger, String condition, String actions, String extra) throws Exception {
        return "{\"name\":\"" + name + "\",\"trigger\":\"" + trigger + "\",\"condition\":" + json.writeValueAsString(condition)
                + ",\"actions\":" + actions + (extra == null ? "" : "," + extra) + "}";
    }

    @Test
    void eventRulesAssignCommentAndDoNotLoop() throws Exception {
        Account alice = register();
        Account bob = register();
        Account carol = register();
        String key = project(alice, bob, carol);
        // Bob already has work, so least-loaded picks Carol (Alice is away).
        task(alice, key, "Bob's existing work", "assigneeId", bob.id());
        perform(put("/api/profile/away"), alice, "{\"until\":\"" + java.time.LocalDate.now().plusDays(2) + "\"}");

        perform(post("/api/projects/" + key + "/automations"), alice, rule("Triage bugs", "CREATED", "type = bug",
                "[{\"type\":\"assign\",\"value\":\"least_loaded\"},{\"type\":\"set_priority\",\"value\":\"HIGH\"},"
                        + "{\"type\":\"add_label\",\"value\":\"triage\"}]", null)).andExpect(status().isCreated());
        perform(post("/api/projects/" + key + "/automations"), alice, rule("Thank on done", "STATUS_CHANGED", "",
                "[{\"type\":\"comment\",\"value\":\"Thanks {{assignee}} for finishing {{key}}!\"},"
                        + "{\"type\":\"set_status\",\"value\":\"IN_REVIEW\"}]", "\"triggerStatus\":\"DONE\"")).andExpect(status().isCreated());

        JsonNode bug = task(bob, key, "Crash", "type", "BUG");
        JsonNode after = getJson("/api/tasks/" + bug.get("id").asLong(), alice);
        assertThat(after.get("assignee").get("id").asLong()).isEqualTo(carol.id());
        assertThat(after.get("priority").asText()).isEqualTo("HIGH");
        assertThat(after.get("labels").toString()).contains("triage");
        String activity = getJson("/api/tasks/" + bug.get("id").asLong() + "/activity", alice).toString();
        assertThat(activity).contains("(rule “Triage bugs”)");

        JsonNode story = task(bob, key, "Not a bug");
        assertThat(getJson("/api/tasks/" + story.get("id").asLong(), alice).get("assignee").isNull()).isTrue();

        // Moving to Done adds a comment; the rule's own status change does not trigger rules again.
        perform(patch("/api/tasks/" + bug.get("id").asLong() + "/status"), carol, body("status", "DONE"));
        JsonNode comments = getJson("/api/tasks/" + bug.get("id").asLong() + "/comments", alice);
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).get("body").asText()).isEqualTo("Thanks @" + carol.username() + " for finishing " + bug.get("key").asText() + "!");
        assertThat(getJson("/api/tasks/" + bug.get("id").asLong(), alice).get("status").asText()).isEqualTo("IN_REVIEW");

        JsonNode rules = getJson("/api/projects/" + key + "/automations", bob);
        assertThat(rules.get(0).get("runCount").asInt()).isEqualTo(1);
        JsonNode log = getJson("/api/automations/" + rules.get(0).get("id").asLong() + "/log", bob);
        assertThat(log.get(0).get("message").asText()).contains("assigned to " + carol.username());

        // Validation.
        perform(post("/api/projects/" + key + "/automations"), alice, rule("Bad", "CREATED", "",
                "[{\"type\":\"set_status\",\"value\":\"SOMEDAY\"}]", null)).andExpect(status().isBadRequest());
        perform(post("/api/projects/" + key + "/automations"), alice, rule("Bad", "SCHEDULED", "",
                "[{\"type\":\"add_label\",\"value\":\"x\"}]", null)).andExpect(jsonPath("$.fieldErrors.condition").exists());
        perform(post("/api/projects/" + key + "/automations"), alice, rule("Bad", "CREATED", "colour = red",
                "[{\"type\":\"add_label\",\"value\":\"x\"}]", null)).andExpect(jsonPath("$.fieldErrors.condition").exists());
    }

    @Test
    void scheduledRulesEscalateOnceAndCleanUpStaleWork() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long urgent = task(alice, key, "Server down", "priority", "CRITICAL").get("id").asLong();
        task(alice, key, "Nice to have", "priority", "LOW");
        long ruleId = read(perform(post("/api/projects/" + key + "/automations"), alice, rule("SLA: critical untouched",
                "SCHEDULED", "priority = critical AND status = todo AND created < +1h",
                "[{\"type\":\"add_label\",\"value\":\"sla-breach\"},{\"type\":\"notify\",\"value\":\"" + bob.username() + "\"},"
                        + "{\"type\":\"assign\",\"value\":\"round_robin\"}]", null))).get("id").asLong();

        automation.runScheduled();
        JsonNode escalated = getJson("/api/tasks/" + urgent, alice);
        assertThat(escalated.get("labels").toString()).contains("sla-breach");
        assertThat(escalated.get("assignee").isNull()).isFalse();
        assertThat(getJson("/api/notifications", bob).get("items").toString()).contains("SLA: critical untouched");
        // Still matching: not again.
        perform(post("/api/automations/" + ruleId + "/run"), alice).andExpect(jsonPath("$.tasks").value(0));

        // Stops matching, then matches again: fires again.
        perform(patch("/api/tasks/" + urgent + "/status"), alice, body("status", "IN_PROGRESS"));
        automation.runScheduled();
        perform(patch("/api/tasks/" + urgent + "/status"), alice, body("status", "TODO"));
        perform(post("/api/automations/" + ruleId + "/run"), alice).andExpect(jsonPath("$.tasks").value(1));

        // Hours in queries.
        assertThat(read(perform(get("/api/search").param("q", "project = " + key + " AND created > -1h"), alice)).get("total").asInt())
                .isEqualTo(2);
        assertThat(read(perform(get("/api/search").param("q", "project = " + key + " AND created < -1h"), alice)).get("total").asInt())
                .isZero();
        perform(delete("/api/automations/" + ruleId), bob).andExpect(status().isNoContent());
    }

    @Test
    void webhooksDeliverSignedEvents() throws Exception {
        List<Map<String, String>> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(Map.of("event", exchange.getRequestHeaders().getFirst("X-FakeJIRA-Event"),
                    "signature", exchange.getRequestHeaders().getFirst("X-FakeJIRA-Signature"), "body", body));
            exchange.sendResponseHeaders(received.size() == 1 ? 200 : 204, -1);
            exchange.close();
        });
        server.start();
        try {
            Account alice = register();
            Account bob = register();
            String key = project(alice, bob);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            perform(post("/api/projects/" + key + "/webhooks"), bob, "{\"url\":\"" + url + "\"}").andExpect(status().isForbidden());
            JsonNode hook = read(perform(post("/api/projects/" + key + "/webhooks"), alice,
                    "{\"url\":\"" + url + "\",\"events\":[\"task.created\",\"task.status_changed\"]}").andExpect(status().isCreated()));
            String secret = hook.get("secret").asText();
            assertThat(secret).hasSize(48);
            assertThat(getJson("/api/projects/" + key + "/webhooks", alice).get(0).get("secret").isNull()).isTrue();

            long id = task(bob, key, "Hooked").get("id").asLong();
            perform(post("/api/tasks/" + id + "/comments"), bob, body("body", "not subscribed"));
            perform(patch("/api/tasks/" + id + "/status"), bob, body("status", "DONE"));
            long deadline = System.currentTimeMillis() + 5000;
            while (received.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(received).hasSize(2);
            assertThat(received.get(0).get("event")).isEqualTo("task.created");
            assertThat(received.get(0).get("signature")).isEqualTo("sha256=" + WebhookService.sign(secret, received.get(0).get("body")));
            JsonNode payload = json.readTree(received.get(1).get("body"));
            assertThat(payload.get("event").asText()).isEqualTo("task.status_changed");
            assertThat(payload.get("task").get("title").asText()).isEqualTo("Hooked");
            assertThat(payload.get("details").get("to").asText()).isEqualTo("DONE");
            assertThat(payload.get("actor").get("username").asText()).isEqualTo(bob.username());

            JsonNode ping = read(perform(post("/api/webhooks/" + hook.get("id").asLong() + "/test"), alice).andExpect(status().isOk()));
            assertThat(ping.get("status").asInt()).isEqualTo(204);
            deadline = System.currentTimeMillis() + 3000;
            while (getJson("/api/webhooks/" + hook.get("id").asLong() + "/deliveries", alice).size() < 3
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(getJson("/api/webhooks/" + hook.get("id").asLong() + "/deliveries", alice)).hasSize(3);
            perform(post("/api/projects/" + key + "/webhooks"), alice, "{\"url\":\"ftp://x\"}").andExpect(status().isBadRequest());
            perform(delete("/api/webhooks/" + hook.get("id").asLong()), alice).andExpect(status().isNoContent());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void apiTokensAreScopedAndRevocable() throws Exception {
        Account alice = register();
        String key = project(alice);
        JsonNode read = read(perform(post("/api/profile/tokens"), alice, "{\"name\":\"CI read\",\"scope\":\"READ\"}")
                .andExpect(status().isCreated()));
        String readToken = read.get("token").asText();
        assertThat(readToken).startsWith("fjt_");
        String writeToken = read(perform(post("/api/profile/tokens"), alice,
                "{\"name\":\"Bot\",\"scope\":\"WRITE\",\"expiresInDays\":30}")).get("token").asText();

        mvc.perform(get("/api/tasks?project=" + key).header("Authorization", "Bearer " + readToken)).andExpect(status().isOk());
        String create = json.writeValueAsString(Map.of("projectKey", key, "title", "From a script", "priority", "LOW"));
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + readToken).contentType(MediaType.APPLICATION_JSON)
                .content(create)).andExpect(status().isForbidden());
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + writeToken).contentType(MediaType.APPLICATION_JSON)
                .content(create)).andExpect(status().isCreated());
        // Tokens cannot manage the account (or create more tokens).
        mvc.perform(get("/api/profile/tokens").header("Authorization", "Bearer " + writeToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + writeToken)).andExpect(status().isOk());

        JsonNode list = getJson("/api/profile/tokens", alice);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("token").isNull()).isTrue();
        assertThat(list.get(1).get("lastUsedAt").isNull()).isFalse();
        perform(delete("/api/profile/tokens/" + read.get("id").asLong()), alice).andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + readToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer fjt_nonsense")).andExpect(status().isUnauthorized());
    }

    @Test
    void gitlabAndGiteaLinkCommitsAndMergeRequests() throws Exception {
        Account alice = register();
        String key = project(alice);
        long id = task(alice, key, "Fix login").get("id").asLong();
        String secret = read(perform(post("/api/projects/" + key + "/github"), alice)).get("secret").asText();
        perform(put("/api/projects/" + key + "/github"), alice, "{\"autoDone\":true}");

        String push = "{\"user_username\":\"alice\",\"commits\":[{\"id\":\"abc1234def\",\"message\":\"" + key + "-1 fix the form\","
                + "\"url\":\"https://gitlab.example.com/c/abc\",\"author\":{\"name\":\"Alice\"}}]}";
        mvc.perform(post("/api/integrations/gitlab/" + key).header("X-Gitlab-Event", "Push Hook").header("X-Gitlab-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(push)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/integrations/gitlab/" + key).header("X-Gitlab-Event", "Push Hook").header("X-Gitlab-Token", secret)
                .contentType(MediaType.APPLICATION_JSON).content(push)).andExpect(jsonPath("$.linked").value(1));
        String mr = "{\"user\":{\"username\":\"alice\"},\"project\":{\"path_with_namespace\":\"team/app\"},\"object_attributes\":"
                + "{\"iid\":7,\"title\":\"" + key + "-1 login fix\",\"description\":\"\",\"source_branch\":\"fix\",\"url\":\"https://gitlab.example.com/mr/7\","
                + "\"state\":\"merged\",\"action\":\"merge\"}}";
        mvc.perform(post("/api/integrations/gitlab/" + key).header("X-Gitlab-Event", "Merge Request Hook").header("X-Gitlab-Token", secret)
                .contentType(MediaType.APPLICATION_JSON).content(mr)).andExpect(jsonPath("$.completed").value(1));
        assertThat(getJson("/api/tasks/" + id, alice).get("status").asText()).isEqualTo("DONE");

        long other = task(alice, key, "Second").get("id").asLong();
        String gitea = "{\"commits\":[{\"id\":\"feedbeef\",\"message\":\"refs " + key + "-2\",\"url\":\"https://gitea.example.com/c\","
                + "\"author\":{\"username\":\"alice\"}}]}";
        String signature = WebhookService.sign(secret, gitea);
        mvc.perform(post("/api/integrations/gitea/" + key).header("X-Gitea-Event", "push").header("X-Gitea-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).content(gitea)).andExpect(jsonPath("$.linked").value(1));
        mvc.perform(post("/api/integrations/gitea/" + key).header("X-Gitea-Event", "push").header("X-Gitea-Signature", "00")
                .contentType(MediaType.APPLICATION_JSON).content(gitea)).andExpect(status().isUnauthorized());
        assertThat(getJson("/api/tasks/" + other + "/dev", alice)).hasSize(1);
    }

    @Test
    void shareLinksShowOneTaskPublicly() throws Exception {
        Account alice = register();
        Account viewer = register();
        String key = project(alice);
        perform(post("/api/projects/" + key + "/members"), alice, body("login", viewer.username(), "role", "VIEWER"));
        long id = task(alice, key, "Public roadmap item", "description", "We are working on it", "checklist", List.of("Design")).get("id").asLong();
        perform(post("/api/tasks/" + id + "/comments"), alice, body("body", "Internal note"));
        perform(post("/api/tasks/" + id + "/shares"), viewer, "{\"includeComments\":false}").andExpect(status().isForbidden());
        JsonNode link = read(perform(post("/api/tasks/" + id + "/shares"), alice, "{\"includeComments\":false,\"expiresInDays\":7}")
                .andExpect(status().isCreated()));
        String token = link.get("url").asText().replaceAll(".*/share/", "");
        JsonNode shared = read(mvc.perform(get("/api/public/share/" + token)).andExpect(status().isOk()));
        assertThat(shared.get("title").asText()).isEqualTo("Public roadmap item");
        assertThat(shared.get("checklist")).hasSize(1);
        assertThat(shared.get("comments")).isEmpty();
        assertThat(shared.toString()).doesNotContain("@example.com");
        JsonNode withComments = read(perform(post("/api/tasks/" + id + "/shares"), alice, "{\"includeComments\":true}"));
        String token2 = withComments.get("url").asText().replaceAll(".*/share/", "");
        assertThat(read(mvc.perform(get("/api/public/share/" + token2))).get("comments").get(0).get("body").asText()).isEqualTo("Internal note");
        assertThat(getJson("/api/tasks/" + id + "/shares", alice).get(1).get("views").asInt()).isEqualTo(1);

        perform(delete("/api/shares/" + link.get("id").asLong()), alice).andExpect(status().isNoContent());
        mvc.perform(get("/api/public/share/" + token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/share/short")).andExpect(status().isNotFound());
    }

    @Test
    void importsJiraCsvAndTrelloJson() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        String csv = "Summary,Issue key,Issue id,Parent,Issue Type,Status,Priority,Assignee,Labels,Labels,Due Date,Description,Custom field (Story Points)\n"
                + "Checkout revamp,OLD-1,100,,Epic,To Do,Medium,,,,,The big one,\n"
                + "Pay with card,OLD-2,101,100,Story,In Progress,Highest," + bob.username() + ",payments,web,12/Oct/26 12:00 AM,\"h2. Goal\n*Fast* payments\",5\n"
                + "Write tests,OLD-3,102,101,Sub-task,Done,Low,,,,,,\n"
                + "Old bug,OLD-4,103,,Bug,Code Review,Minor,nobody,,,,,\n"
                + ",OLD-5,104,,Task,To Do,,,,,,,\n";
        JsonNode result = read(mvc.perform(multipart("/api/projects/" + key + "/import/jira")
                .file(new MockMultipartFile("file", "jira.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + alice.token())).andExpect(status().isOk()));
        assertThat(result.get("created").asInt()).isEqualTo(3);
        assertThat(result.get("errors")).hasSize(1);
        assertThat(getJson("/api/projects/" + key + "/epics", alice).get(0).get("name").asText()).isEqualTo("Checkout revamp");
        JsonNode story = getJson("/api/tasks?project=" + key + "&q=card", alice).get(0);
        assertThat(story.get("type").asText()).isEqualTo("STORY");
        assertThat(story.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(story.get("priority").asText()).isEqualTo("CRITICAL");
        assertThat(story.get("assignee").get("id").asLong()).isEqualTo(bob.id());
        assertThat(story.get("epic").get("name").asText()).isEqualTo("Checkout revamp");
        assertThat(story.get("storyPoints").asInt()).isEqualTo(5);
        assertThat(story.get("dueDate").asText()).isEqualTo("2026-10-12");
        assertThat(story.get("labels").toString()).contains("payments", "web", "jira");
        assertThat(story.get("description").asText()).startsWith("## Goal\n**Fast** payments").contains("Imported from Jira OLD-2");
        assertThat(story.get("subtaskTotal").asInt()).isEqualTo(1);
        assertThat(getJson("/api/tasks?project=" + key + "&q=Old bug", alice).get(0).get("status").asText()).isEqualTo("IN_REVIEW");

        String trello = "{\"name\":\"Board\",\"lists\":[{\"id\":\"l1\",\"name\":\"To Do\"},{\"id\":\"l2\",\"name\":\"Doing\"},"
                + "{\"id\":\"l3\",\"name\":\"Done\"},{\"id\":\"l4\",\"name\":\"Archive\",\"closed\":true}],"
                + "\"members\":[{\"id\":\"m1\",\"username\":\"" + bob.username() + "\"}],"
                + "\"cards\":[{\"id\":\"c1\",\"name\":\"Plan launch\",\"desc\":\"Details\",\"idList\":\"l2\",\"idMembers\":[\"m1\"],"
                + "\"labels\":[{\"name\":\"Marketing\",\"color\":\"green\"}],\"due\":\"2026-11-01T12:00:00.000Z\"},"
                + "{\"id\":\"c2\",\"name\":\"Shipped thing\",\"idList\":\"l3\"},{\"id\":\"c3\",\"name\":\"Archived\",\"idList\":\"l4\"},"
                + "{\"id\":\"c4\",\"name\":\"Closed card\",\"idList\":\"l1\",\"closed\":true}],"
                + "\"checklists\":[{\"id\":\"k1\",\"idCard\":\"c1\",\"checkItems\":[{\"name\":\"Press\",\"state\":\"complete\"},{\"name\":\"Blog\",\"state\":\"incomplete\"}]}],"
                + "\"actions\":[{\"type\":\"commentCard\",\"data\":{\"card\":{\"id\":\"c1\"},\"text\":\"Looks good\"},\"memberCreator\":{\"fullName\":\"Dana\"}}]}";
        JsonNode tr = read(mvc.perform(multipart("/api/projects/" + key + "/import/trello")
                .file(new MockMultipartFile("file", "board.json", "application/json", trello.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + alice.token())).andExpect(status().isOk()));
        assertThat(tr.get("created").asInt()).isEqualTo(2);
        JsonNode card = getJson("/api/tasks?project=" + key + "&q=Plan launch", alice).get(0);
        assertThat(card.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(card.get("assignee").get("id").asLong()).isEqualTo(bob.id());
        assertThat(card.get("checklistTotal").asInt()).isEqualTo(2);
        assertThat(card.get("checklistDone").asInt()).isEqualTo(1);
        assertThat(card.get("labels").toString()).contains("marketing", "list-doing", "trello");
        assertThat(getJson("/api/tasks/" + card.get("id").asLong() + "/comments", alice).get(0).get("body").asText())
                .isEqualTo("**Dana** (Trello): Looks good");
        assertThat(getJson("/api/tasks?project=" + key + "&q=Shipped", alice).get(0).get("status").asText()).isEqualTo("DONE");
    }
}
