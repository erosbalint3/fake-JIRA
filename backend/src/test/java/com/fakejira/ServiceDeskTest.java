package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public portal, request forms, the requester conversation, duplicate detection, public roadmap and changelog. */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceDeskTest extends ApiTestSupport {

    private String j(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private JsonNode publicGet(String url) throws Exception {
        return read(mvc.perform(get(url)).andExpect(status().isOk()));
    }

    private org.springframework.test.web.servlet.ResultActions publicPost(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(j(body)));
    }

    private Map<String, Object> request(String summary, Object... extra) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Carol Customer");
        body.put("email", "carol@example.org");
        body.put("summary", summary);
        body.put("description", "It happens every time.");
        for (int i = 0; i < extra.length; i += 2) {
            body.put((String) extra[i], extra[i + 1]);
        }
        return body;
    }

    @Test
    void portalRequestLifecycle() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);

        // Off by default: the portal does not exist publicly.
        mvc.perform(get("/api/public/portal/" + key)).andExpect(status().isNotFound());
        perform(put("/api/projects/" + key + "/service-desk"), bob, j(Map.of("portalEnabled", true)))
                .andExpect(status().isForbidden());
        JsonNode settings = read(perform(put("/api/projects/" + key + "/service-desk"), alice,
                j(Map.of("portalEnabled", true, "intro", "Hi! How can we help?"))).andExpect(status().isOk()));
        assertThat(settings.get("requestTypes")).hasSize(1);
        assertThat(settings.get("widgetSnippet").asText()).contains("widget.js").contains(key);

        // A bug form with a required choice and an optional URL.
        JsonNode bugType = read(perform(post("/api/projects/" + key + "/request-types"), alice, j(Map.of(
                "name", "Report a bug", "taskType", "BUG", "priority", "HIGH",
                "fields", List.of(
                        Map.of("label", "Browser", "kind", "select", "required", true, "options", List.of("Chrome", "Firefox")),
                        Map.of("label", "Page address", "kind", "url"))))).andExpect(status().isCreated()));
        assertThat(bugType.get("fields").get(0).get("id").asText()).isEqualTo("browser");
        perform(post("/api/projects/" + key + "/request-types"), alice, j(Map.of("name", "Bad",
                "fields", List.of(Map.of("label", "Pick", "kind", "select"))))).andExpect(status().isBadRequest());
        long typeId = bugType.get("id").asLong();

        JsonNode page = publicGet("/api/public/portal/" + key);
        assertThat(page.get("intro").asText()).isEqualTo("Hi! How can we help?");
        assertThat(texts(page.get("requestTypes"), "name")).containsExactly("Get help", "Report a bug");

        publicPost("/api/public/portal/" + key + "/requests", request("Checkout broken", "requestTypeId", typeId))
                .andExpect(status().isBadRequest());
        publicPost("/api/public/portal/" + key + "/requests", request("Checkout broken", "requestTypeId", typeId,
                "answers", Map.of("browser", "Safari"))).andExpect(status().isBadRequest());
        publicPost("/api/public/portal/" + key + "/requests", request("Checkout broken", "requestTypeId", typeId,
                "email", "not-an-email", "answers", Map.of("browser", "Chrome"))).andExpect(status().isBadRequest());
        JsonNode created = read(publicPost("/api/public/portal/" + key + "/requests", request("Checkout broken",
                "requestTypeId", typeId, "answers", Map.of("browser", "Chrome", "page-address", "https://shop.example/cart")))
                .andExpect(status().isCreated()));
        String token = created.get("token").asText();
        String reference = created.get("reference").asText();

        // The team sees a bug with the answers, labelled "portal", and a notification.
        JsonNode task = getJson("/api/tasks/key/" + reference, bob);
        assertThat(task.get("type").asText()).isEqualTo("BUG");
        assertThat(task.get("priority").asText()).isEqualTo("HIGH");
        assertThat(task.get("labels").toString()).contains("portal");
        assertThat(task.get("description").asText()).contains("Browser: Chrome").contains("carol@example.org");
        assertThat(texts(getJson("/api/notifications", bob).get("items"), "message"))
                .anyMatch(m -> m.startsWith("New request from Carol Customer"));
        long taskId = task.get("id").asLong();
        JsonNode info = getJson("/api/tasks/" + taskId + "/portal", bob);
        assertThat(info.get("requesterEmail").asText()).isEqualTo("carol@example.org");
        assertThat(info.get("answers").get(1).get("value").asText()).isEqualTo("https://shop.example/cart");

        // Conversation: the team replies, the requester answers; internal comments stay internal.
        perform(post("/api/tasks/" + taskId + "/comments"), bob, body("body", "Internal: probably the payment service"))
                .andExpect(status().isCreated());
        perform(post("/api/tasks/" + taskId + "/portal/messages"), bob, body("body", "Thanks, we are on it!"))
                .andExpect(status().isCreated());
        publicPost("/api/public/requests/" + token + "/messages", Map.of("body", "Great, thank you")).andExpect(status().isCreated());
        JsonNode tracking = publicGet("/api/public/requests/" + token);
        assertThat(tracking.get("status").asText()).isEqualTo("Waiting for the team");
        assertThat(texts(tracking.get("messages"), "body")).containsExactly("Thanks, we are on it!", "Great, thank you");
        assertThat(tracking.toString()).doesNotContain("Internal").doesNotContain(bob.username() + "@example.com");
        assertThat(texts(getJson("/api/notifications", alice).get("items"), "message"))
                .anyMatch(m -> m.contains("(requester) replied"));

        perform(patch("/api/tasks/" + taskId + "/status"), bob, body("status", "DONE")).andExpect(status().isOk());
        assertThat(publicGet("/api/public/requests/" + token).get("resolved").asBoolean()).isTrue();
        mvc.perform(get("/api/public/requests/not-a-real-token-at-all-xyz")).andExpect(status().isNotFound());

        // Honeypot: bots get a success response and no task.
        int before = getJson("/api/tasks?project=" + key, alice).size();
        publicPost("/api/public/portal/" + key + "/requests", request("Buy cheap stuff", "website", "http://spam"))
                .andExpect(status().isCreated());
        assertThat(getJson("/api/tasks?project=" + key, alice)).hasSize(before);

        // Deleting the task removes the request; its link stops working.
        perform(delete("/api/tasks/" + taskId), alice).andExpect(status().isNoContent());
        mvc.perform(get("/api/public/requests/" + token)).andExpect(status().isNotFound());
    }

    @Test
    void duplicateDetection() throws Exception {
        Account alice = register();
        String key = project(alice);
        long login = task(alice, key, "Login button does nothing on Safari").get("id").asLong();
        task(alice, key, "Update the privacy policy text");
        task(alice, key, "Dark mode colours are too bright");
        JsonNode similar = getJson("/api/projects/" + key + "/similar?q=Cannot log in, the login button is broken", alice);
        assertThat(similar).isNotEmpty();
        assertThat(similar.get(0).get("task").get("id").asLong()).isEqualTo(login);
        assertThat(getJson("/api/projects/" + key + "/similar?q=Cannot log in&exclude=" + login, alice)).isEmpty();
        assertThat(getJson("/api/projects/" + key + "/similar?q=completely unrelated words here", alice)).isEmpty();
        perform(get("/api/projects/" + key + "/similar?q=login"), register()).andExpect(status().isNotFound());
    }

    @Test
    void publicRoadmapAndChangelog() throws Exception {
        Account alice = register();
        String key = project(alice);
        mvc.perform(get("/api/public/projects/" + key + "/roadmap")).andExpect(status().isNotFound());
        perform(put("/api/projects/" + key + "/service-desk"), alice,
                j(Map.of("portalEnabled", true, "roadmapPublic", true, "changelogPublic", true))).andExpect(status().isOk());

        long epic = read(perform(post("/api/projects/" + key + "/epics"), alice, j(Map.of("name", "Offline mode",
                "description", "Use the app without a connection"))).andExpect(status().isCreated())).get("id").asLong();
        long work = task(alice, key, "Cache pages", "epicId", epic).get("id").asLong();
        perform(patch("/api/tasks/" + work + "/status"), alice, body("status", "IN_PROGRESS")).andExpect(status().isOk());
        JsonNode roadmap = publicGet("/api/public/projects/" + key + "/roadmap");
        assertThat(roadmap.get("items").get(0).get("name").asText()).isEqualTo("Offline mode");
        assertThat(roadmap.get("items").get(0).get("stage").asText()).isEqualTo("now");

        long release = read(perform(post("/api/projects/" + key + "/releases"), alice, j(Map.of("name", "2.0")))
                .andExpect(status().isCreated())).get("id").asLong();
        long fix = task(alice, key, "Fix crash on start", "type", "BUG").get("id").asLong();
        long feature = task(alice, key, "Export to PDF", "type", "STORY").get("id").asLong();
        long secret = task(alice, key, "Internal refactor").get("id").asLong();
        for (long id : List.of(fix, feature, secret)) {
            perform(put("/api/tasks/" + id + "/release"), alice, j(Map.of("releaseId", release))).andExpect(status().isOk());
        }
        for (long id : List.of(fix, feature)) {
            perform(patch("/api/tasks/" + id + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        }
        assertThat(publicGet("/api/public/projects/" + key + "/changelog").get("releases")).isEmpty();
        perform(post("/api/releases/" + release + "/release"), alice, "{}").andExpect(status().isOk());
        JsonNode changelog = publicGet("/api/public/projects/" + key + "/changelog");
        JsonNode entry = changelog.get("releases").get(0);
        assertThat(entry.get("version").asText()).isEqualTo("2.0");
        assertThat(entry.get("fixes").get(0).asText()).isEqualTo("Fix crash on start");
        assertThat(entry.get("features").get(0).asText()).isEqualTo("Export to PDF");
        assertThat(entry.toString()).doesNotContain("Internal refactor");

        // The portal's "already known?" check only looks at public items.
        JsonNode matches = publicGet("/api/public/portal/" + key + "/similar?q=app crashes on start");
        assertThat(matches.get(0).get("title").asText()).isEqualTo("Fix crash on start");
        assertThat(matches.get(0).get("kind").asText()).isEqualTo("changelog");
    }

    @Test
    void widgetPageMayBeFramed() throws Exception {
        mvc.perform(get("/embed/WEB")).andExpect(header().doesNotExist("X-Frame-Options"));
        mvc.perform(get("/portal/WEB")).andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
    }
}
