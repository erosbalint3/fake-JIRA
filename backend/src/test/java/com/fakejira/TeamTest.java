package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TeamTest extends ApiTestSupport {

    @Test
    void viewersCanReadAndWatchButNotChangeAnything() throws Exception {
        Account owner = register();
        Account viewer = register();
        String key = project(owner);
        perform(post("/api/projects/" + key + "/members"), owner,
                body("login", viewer.username(), "role", "VIEWER")).andExpect(status().isOk());
        JsonNode project = getJson("/api/projects/" + key, owner);
        assertThat(project.get("members").toString()).contains("\"role\":\"VIEWER\"", "\"role\":\"OWNER\"");

        long id = task(owner, key, "Read me").get("id").asLong();
        perform(get("/api/tasks/" + id), viewer).andExpect(status().isOk());
        perform(post("/api/tasks"), viewer, body("projectKey", key, "title", "x", "priority", "LOW"))
                .andExpect(status().isForbidden());
        perform(patch("/api/tasks/" + id + "/status"), viewer, body("status", "DONE")).andExpect(status().isForbidden());
        perform(post("/api/tasks/" + id + "/comments"), viewer, body("body", "hi")).andExpect(status().isForbidden());
        perform(post("/api/tasks/" + id + "/accept"), viewer).andExpect(status().isForbidden());
        perform(post("/api/projects/" + key + "/sprints"), viewer, "{}").andExpect(status().isForbidden());
        perform(put("/api/tasks/" + id + "/assignee"), owner, "{\"assigneeId\":" + viewer.id() + "}")
                .andExpect(status().isBadRequest());
        perform(put("/api/tasks/" + id + "/watch"), viewer).andExpect(jsonPath("$.watching").value(true));

        // Promoting to member unlocks editing.
        perform(put("/api/projects/" + key + "/members/" + viewer.id() + "/role"), owner, body("role", "MEMBER"))
                .andExpect(status().isOk());
        perform(patch("/api/tasks/" + id + "/status"), viewer, body("status", "IN_PROGRESS")).andExpect(status().isOk());
    }

    @Test
    void watchersGetNotificationsAndCommentersAutoWatch() throws Exception {
        Account alice = register();
        Account watcher = register();
        Account commenter = register();
        String key = project(alice, watcher, commenter);
        long id = task(alice, key, "Watch me").get("id").asLong();
        for (Account a : new Account[]{watcher, commenter}) {
            perform(post("/api/notifications/read-all"), a);
        }
        perform(put("/api/tasks/" + id + "/watch"), watcher).andExpect(jsonPath("$.watchers.length()").value(1));
        perform(post("/api/tasks/" + id + "/comments"), commenter, body("body", "I'll follow this"))
                .andExpect(status().isCreated());
        perform(patch("/api/tasks/" + id + "/status"), alice, body("status", "IN_PROGRESS"));

        perform(get("/api/notifications"), watcher)
                .andExpect(jsonPath("$.items[0].message").value(containsString("moved to in progress")));
        perform(get("/api/notifications"), commenter)
                .andExpect(jsonPath("$.items[0].message").value(containsString("moved to in progress")));
        perform(get("/api/tasks/" + id + "/watchers"), commenter).andExpect(jsonPath("$.watching").value(true));

        perform(delete("/api/tasks/" + id + "/watch"), watcher).andExpect(jsonPath("$.watching").value(false));
    }

    @Test
    void savedFiltersArePrivateUnlessShared() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        perform(post("/api/projects/" + key + "/filters"), alice,
                json.writeValueAsString(Map.of("name", "My bugs", "query", "?assignee=me&label=bug", "shared", false)))
                .andExpect(jsonPath("$.query").value("assignee=me&label=bug"));
        long shared = read(perform(post("/api/projects/" + key + "/filters"), alice,
                json.writeValueAsString(Map.of("name", "Critical", "query", "priority=CRITICAL", "shared", true))))
                .get("id").asLong();

        assertThat(texts(getJson("/api/projects/" + key + "/filters", alice), "name")).containsExactly("Critical", "My bugs");
        assertThat(texts(getJson("/api/projects/" + key + "/filters", bob), "name")).containsExactly("Critical");
        perform(delete("/api/filters/" + shared), bob).andExpect(status().isNotFound());
        perform(delete("/api/filters/" + shared), alice).andExpect(status().isNoContent());
    }

    @Test
    void timeTrackingTotalsAndReport() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long a = task(alice, key, "Design").get("id").asLong();
        long b = task(alice, key, "Build").get("id").asLong();
        perform(post("/api/tasks/" + a + "/time"), alice, "{\"minutes\":90,\"note\":\"wireframes\"}").andExpect(status().isCreated());
        long bobEntry = read(perform(post("/api/tasks/" + a + "/time"), bob, "{\"minutes\":30}")).get("id").asLong();
        perform(post("/api/tasks/" + b + "/time"), bob, "{\"minutes\":120,\"date\":\"" + LocalDate.now().minusDays(3) + "\"}");
        perform(post("/api/tasks/" + b + "/time"), bob, "{\"minutes\":0}").andExpect(status().isBadRequest());
        perform(post("/api/tasks/" + b + "/time"), bob,
                "{\"minutes\":10,\"date\":\"" + LocalDate.now().plusDays(5) + "\"}").andExpect(status().isBadRequest());

        perform(get("/api/tasks/" + a), alice).andExpect(jsonPath("$.timeSpentMinutes").value(120));
        assertThat(texts(getJson("/api/tasks/" + a + "/activity", alice), "message")).contains("logged 1h 30m");

        JsonNode report = getJson("/api/projects/" + key + "/time", alice);
        assertThat(report.get("totalMinutes").asInt()).isEqualTo(240);
        assertThat(report.get("byUser").get(0).get("user").get("id").asLong()).isEqualTo(bob.id());
        assertThat(report.get("byUser").get(0).get("minutes").asInt()).isEqualTo(150);
        assertThat(report.get("byTask").get(0).get("minutes").asInt()).isEqualTo(120);

        Account carol = register();
        perform(delete("/api/tasks/" + a + "/time/" + bobEntry), carol).andExpect(status().isNotFound());
        perform(delete("/api/tasks/" + a + "/time/" + bobEntry), alice).andExpect(status().isNoContent());
    }

    @Test
    void taskLookupByKey() throws Exception {
        Account alice = register();
        String key = project(alice);
        long id = task(alice, key, "Find me").get("id").asLong();
        perform(get("/api/tasks/key/" + key.toLowerCase() + "-1"), alice).andExpect(jsonPath("$.id").value(id));
        perform(get("/api/tasks/key/" + key + "-99"), alice).andExpect(status().isNotFound());
        perform(get("/api/tasks/key/" + key + "-1"), register()).andExpect(status().isNotFound());
    }
}
