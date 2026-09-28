package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectApiTest extends ApiTestSupport {

    @Test
    void projectKeysNumberTasksIndependently() throws Exception {
        Account owner = register();
        String web = project(owner);
        String api = project(owner);

        assertThat(task(owner, web, "a").get("key").asText()).isEqualTo(web + "-1");
        assertThat(task(owner, web, "b").get("key").asText()).isEqualTo(web + "-2");
        assertThat(task(owner, api, "c").get("key").asText()).isEqualTo(api + "-1");
    }

    @Test
    void validatesAndRejectsDuplicateKeys() throws Exception {
        Account owner = register();
        perform(post("/api/projects"), owner, body("key", "1BAD", "name", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.key").exists());
        String key = project(owner);
        perform(post("/api/projects"), owner, body("key", key.toLowerCase(), "name", "again"))
                .andExpect(status().isConflict());
    }

    @Test
    void nonMembersCannotSeeProjectOrItsTasks() throws Exception {
        Account owner = register();
        Account outsider = register();
        String key = project(owner);
        long id = task(owner, key, "secret").get("id").asLong();

        perform(get("/api/projects/" + key), outsider).andExpect(status().isNotFound());
        perform(get("/api/tasks/" + id), outsider).andExpect(status().isNotFound());
        perform(get("/api/tasks?project=" + key), outsider).andExpect(status().isNotFound());
        assertThat(ids(getJson("/api/tasks", outsider))).doesNotContain(id);
        perform(post("/api/tasks"), outsider, body("projectKey", key, "title", "x", "priority", "LOW"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerManagesMembersAndMembersCanLeave() throws Exception {
        Account owner = register();
        Account bob = register();
        Account carol = register();
        String key = project(owner, bob);

        // Added members get a notification and see the project.
        perform(get("/api/notifications"), bob).andExpect(jsonPath("$.items[0].message").value(
                org.hamcrest.Matchers.containsString("added you to project " + key)));
        assertThat(texts(getJson("/api/projects", bob), "key")).contains(key);

        // Only the owner manages membership and settings.
        perform(post("/api/projects/" + key + "/members"), bob, body("login", carol.username()))
                .andExpect(status().isForbidden());
        perform(put("/api/projects/" + key), bob, body("name", "Hijack"))
                .andExpect(status().isForbidden());
        perform(post("/api/projects/" + key + "/members"), owner, body("login", "nobody-here"))
                .andExpect(status().isNotFound());
        perform(post("/api/projects/" + key + "/members"), owner, body("login", bob.username()))
                .andExpect(status().isConflict());

        // Removing a member unassigns their tasks there.
        long id = task(owner, key, "for bob", "assigneeId", bob.id()).get("id").asLong();
        perform(delete("/api/projects/" + key + "/members/" + bob.id()), owner).andExpect(status().isNoContent());
        perform(get("/api/tasks/" + id), owner).andExpect(jsonPath("$.assignee").doesNotExist());
        perform(get("/api/projects/" + key), bob).andExpect(status().isNotFound());

        // A member can leave, the owner cannot be removed.
        perform(post("/api/projects/" + key + "/members"), owner, body("login", carol.username()))
                .andExpect(status().isOk());
        perform(delete("/api/projects/" + key + "/members/" + carol.id()), carol).andExpect(status().isNoContent());
        perform(delete("/api/projects/" + key + "/members/" + owner.id()), owner).andExpect(status().isBadRequest());
    }

    @Test
    void ownerCanDeleteProjectWithEverythingInIt() throws Exception {
        Account owner = register();
        Account bob = register();
        String key = project(owner, bob);
        long id = task(owner, key, "doomed").get("id").asLong();
        perform(post("/api/tasks/" + id + "/comments"), bob, body("body", "hi")).andExpect(status().isCreated());
        perform(post("/api/tasks/" + id + "/checklist"), bob, body("text", "step")).andExpect(status().isCreated());
        perform(post("/api/projects/" + key + "/sprints"), owner, "{}").andExpect(status().isCreated());

        perform(delete("/api/projects/" + key), bob).andExpect(status().isForbidden());
        perform(delete("/api/projects/" + key), owner).andExpect(status().isNoContent());
        perform(get("/api/projects/" + key), owner).andExpect(status().isNotFound());
        perform(get("/api/tasks/" + id), owner).andExpect(status().isNotFound());
    }

    @Test
    void userSearchReturnsUsernamesOnly() throws Exception {
        Account me = register();
        Account target = register(uniqueName("findme"));
        JsonNode matches = getJson("/api/users?q=" + target.username().substring(0, 8), me);
        assertThat(texts(matches, "username")).contains(target.username());
        assertThat(matches.get(0).has("email")).isFalse();
        assertThat(getJson("/api/users?q=a", me)).isEmpty();
    }
}
