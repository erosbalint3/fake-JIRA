package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FakeJiraApiTest {

    private static final String PASSWORD = "Secret#123";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
    }

    @Test
    void registerValidatesInputAndRejectsDuplicates() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\",\"email\":\"nope\",\"password\":\"weak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        String name = uniqueName();
        register(name);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("username", name, "email", "other-" + name + "@example.com", "password", PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    void loginWorksWithUsernameOrEmailAndRejectsBadPassword() throws Exception {
        String name = uniqueName();
        register(name);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", name, "password", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", name.toUpperCase() + "@EXAMPLE.COM", "password", PASSWORD)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", name, "password", "Wrong#123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullTaskLifecycle() throws Exception {
        String alice = register(uniqueName());
        String bob = register(uniqueName());

        // Alice creates a task; it shows up as available for Bob.
        JsonNode created = read(mvc.perform(auth(post("/api/tasks"), alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("title", "Fix login", "description", "Button does nothing", "priority", "HIGH")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturn().getResponse().getContentAsString());
        long id = created.get("id").asLong();
        assertThat(created.get("key").asText()).isEqualTo("FJ-" + id);

        assertThat(idsIn(auth(get("/api/tasks?scope=AVAILABLE"), bob))).contains(id);

        // Bob cannot edit or delete a task he has nothing to do with.
        mvc.perform(auth(put("/api/tasks/" + id), bob).contentType(MediaType.APPLICATION_JSON)
                        .content(body("title", "Hijack", "description", "", "priority", "LOW")))
                .andExpect(status().isForbidden());
        mvc.perform(auth(delete("/api/tasks/" + id), bob)).andExpect(status().isForbidden());

        // Bob accepts it; it moves from available to his own tasks and Alice is notified.
        mvc.perform(auth(post("/api/tasks/" + id + "/accept"), bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.username").exists());
        assertThat(idsIn(auth(get("/api/tasks?scope=AVAILABLE"), bob))).doesNotContain(id);
        assertThat(idsIn(auth(get("/api/tasks?scope=MINE"), bob))).contains(id);
        mvc.perform(auth(post("/api/tasks/" + id + "/accept"), alice)).andExpect(status().isConflict());

        mvc.perform(auth(get("/api/notifications"), alice))
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[0].taskId").value(id));

        // Bob moves it along the board and comments.
        mvc.perform(auth(patch("/api/tasks/" + id + "/status"), bob).contentType(MediaType.APPLICATION_JSON)
                        .content(body("status", "IN_PROGRESS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(auth(post("/api/tasks/" + id + "/comments"), bob).contentType(MediaType.APPLICATION_JSON)
                        .content(body("body", "On it")))
                .andExpect(status().isCreated());
        mvc.perform(auth(get("/api/tasks/" + id + "/comments"), alice))
                .andExpect(jsonPath("$[0].body").value("On it"));

        // Search and filters.
        assertThat(idsIn(auth(get("/api/tasks").param("q", "fix LOG"), alice))).contains(id);
        assertThat(idsIn(auth(get("/api/tasks?priority=LOW&scope=REPORTED"), alice))).doesNotContain(id);

        // Profile stats and notification read state.
        mvc.perform(auth(get("/api/profile"), bob))
                .andExpect(jsonPath("$.stats.assigned").value(1))
                .andExpect(jsonPath("$.stats.inProgress").value(1));
        mvc.perform(auth(post("/api/notifications/read-all"), alice)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/notifications/unread-count"), alice)).andExpect(jsonPath("$").value(0));

        // Releasing puts it back in the pool; the reporter can delete it (with its comments).
        mvc.perform(auth(post("/api/tasks/" + id + "/release"), bob))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.assignee").doesNotExist());
        mvc.perform(auth(delete("/api/tasks/" + id), alice)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/tasks/" + id), alice)).andExpect(status().isNotFound());
    }

    @Test
    void changePasswordRequiresCurrentPassword() throws Exception {
        String name = uniqueName();
        String token = register(name);
        mvc.perform(auth(put("/api/profile/password"), token).contentType(MediaType.APPLICATION_JSON)
                        .content(body("currentPassword", "Wrong#123", "newPassword", "Newer#456")))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(put("/api/profile/password"), token).contentType(MediaType.APPLICATION_JSON)
                        .content(body("currentPassword", PASSWORD, "newPassword", "Newer#456")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", name, "password", "Newer#456")))
                .andExpect(status().isOk());
    }

    private String register(String username) throws Exception {
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("username", username, "email", username + "@example.com", "password", PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return read(response).get("token").asText();
    }

    private java.util.List<Long> idsIn(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode array = read(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        java.util.List<Long> ids = new java.util.ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private JsonNode read(String content) throws Exception {
        return json.readTree(content);
    }

    private String body(String... pairs) throws Exception {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return json.writeValueAsString(map);
    }

    private static String uniqueName() {
        return "user" + UUID.randomUUID().toString().substring(0, 8);
    }
}
