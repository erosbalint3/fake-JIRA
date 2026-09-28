package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

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
class FakeJiraApiTest extends ApiTestSupport {

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

        Account account = register();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("username", account.username(), "email", "other-" + account.username() + "@example.com",
                                "password", PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    void loginWorksWithUsernameOrEmailAndRejectsBadPassword() throws Exception {
        Account account = register();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", account.username(), "password", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", account.username().toUpperCase() + "@EXAMPLE.COM", "password", PASSWORD)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", account.username(), "password", "Wrong#123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullTaskLifecycle() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);

        JsonNode created = task(alice, key, "Fix login", "description", "Button does nothing", "priority", "HIGH");
        long id = created.get("id").asLong();
        assertThat(created.get("key").asText()).isEqualTo(key + "-1");
        assertThat(created.get("status").asText()).isEqualTo("TODO");
        assertThat(ids(getJson("/api/tasks?scope=AVAILABLE&project=" + key, bob))).contains(id);

        // Only the reporter or project owner may delete.
        perform(delete("/api/tasks/" + id), bob).andExpect(status().isForbidden());

        perform(post("/api/tasks/" + id + "/accept"), bob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.username").value(bob.username()));
        assertThat(ids(getJson("/api/tasks?scope=AVAILABLE&project=" + key, bob))).doesNotContain(id);
        assertThat(ids(getJson("/api/tasks?scope=MINE", bob))).contains(id);
        perform(post("/api/tasks/" + id + "/accept"), alice).andExpect(status().isConflict());

        perform(get("/api/notifications"), alice)
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[0].taskId").value(id));

        perform(patch("/api/tasks/" + id + "/status"), bob, body("status", "IN_PROGRESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        perform(post("/api/tasks/" + id + "/comments"), bob, body("body", "On it"))
                .andExpect(status().isCreated());
        perform(get("/api/tasks/" + id + "/comments"), alice)
                .andExpect(jsonPath("$[0].body").value("On it"));

        assertThat(ids(read(perform(get("/api/tasks").param("q", "fix LOG"), alice)))).contains(id);
        assertThat(ids(getJson("/api/tasks?priority=LOW&scope=REPORTED", alice))).doesNotContain(id);

        perform(get("/api/profile"), bob)
                .andExpect(jsonPath("$.stats.assigned").value(1))
                .andExpect(jsonPath("$.stats.inProgress").value(1));
        perform(post("/api/notifications/read-all"), alice).andExpect(status().isNoContent());
        perform(get("/api/notifications/unread-count"), alice).andExpect(jsonPath("$").value(0));

        perform(post("/api/tasks/" + id + "/release"), bob)
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.assignee").doesNotExist());
        perform(delete("/api/tasks/" + id), alice).andExpect(status().isNoContent());
        perform(get("/api/tasks/" + id), alice).andExpect(status().isNotFound());
    }

    @Test
    void changePasswordRequiresCurrentPassword() throws Exception {
        Account account = register();
        perform(put("/api/profile/password"), account, body("currentPassword", "Wrong#123", "newPassword", "Newer#456"))
                .andExpect(status().isBadRequest());
        perform(put("/api/profile/password"), account, body("currentPassword", PASSWORD, "newPassword", "Newer#456"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", account.username(), "password", "Newer#456")))
                .andExpect(status().isOk());
    }

    @Test
    void emailNotificationPreferenceIsSaved() throws Exception {
        Account account = register();
        perform(get("/api/profile"), account)
                .andExpect(jsonPath("$.emailNotifications").value(false))
                .andExpect(jsonPath("$.emailAvailable").value(false));
        perform(put("/api/profile/settings"), account, "{\"emailNotifications\":true}")
                .andExpect(jsonPath("$.emailNotifications").value(true));
    }
}
