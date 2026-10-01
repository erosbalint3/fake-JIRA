package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Custom project roles (permission sets) and internal comments hidden from guests, viewers and limited roles. */
@SpringBootTest
@AutoConfigureMockMvc
class RolesAndInternalCommentsTest extends ApiTestSupport {

    private String j(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    @Test
    void customRolesLimitWhatMembersCanDo() throws Exception {
        Account owner = register();
        Account qa = register();
        Account dev = register();
        String key = project(owner, qa, dev);
        long taskId = task(owner, key, "Checkout bug").get("id").asLong();

        JsonNode catalog = getJson("/api/projects/" + key + "/roles", owner);
        assertThat(catalog.get("permissions").size()).isGreaterThanOrEqualTo(9);
        perform(post("/api/projects/" + key + "/roles"), qa, j(Map.of("name", "QA", "permissions", List.of("COMMENT"))))
                .andExpect(status().isForbidden());
        long roleId = read(perform(post("/api/projects/" + key + "/roles"), owner, j(Map.of("name", "QA",
                "description", "Testers", "permissions", List.of("COMMENT", "LOG_TIME")))).andExpect(status().isCreated())).get("id").asLong();
        perform(post("/api/projects/" + key + "/roles"), owner, j(Map.of("name", "qa", "permissions", List.of())))
                .andExpect(status().isBadRequest());
        perform(put("/api/projects/" + key + "/members/" + qa.id() + "/custom-role"), owner, j(Map.of("roleId", roleId))).andExpect(status().isNoContent());

        assertThat(getJson("/api/projects/" + key + "/my-permissions", qa).toString()).contains("COMMENT").contains("LOG_TIME")
                .doesNotContain("CREATE_TASKS");
        assertThat(getJson("/api/projects/" + key + "/my-permissions", dev).size()).isEqualTo(catalog.get("permissions").size());

        // QA can comment and log time...
        perform(post("/api/tasks/" + taskId + "/comments"), qa, body("body", "Reproduced on Safari")).andExpect(status().isCreated());
        perform(post("/api/tasks/" + taskId + "/time"), qa, j(Map.of("minutes", 20))).andExpect(status().isCreated());
        // ...but not create, edit or delete tasks, or run sprints, releases and epics.
        perform(post("/api/tasks"), qa, j(Map.of("projectKey", key, "title", "New", "description", "", "priority", "LOW")))
                .andExpect(status().isForbidden());
        perform(put("/api/tasks/" + taskId), qa, j(Map.of("title", "Renamed", "description", "", "priority", "LOW", "labels", List.of())))
                .andExpect(status().isForbidden());
        perform(delete("/api/tasks/" + taskId), qa).andExpect(status().isForbidden());
        perform(post("/api/projects/" + key + "/sprints"), qa, "{}").andExpect(status().isForbidden());
        perform(post("/api/projects/" + key + "/releases"), qa, body("name", "9.9")).andExpect(status().isForbidden());
        perform(post("/api/projects/" + key + "/epics"), qa, body("name", "Nope")).andExpect(status().isForbidden());
        // Plain members still can.
        perform(post("/api/projects/" + key + "/epics"), dev, body("name", "Yes")).andExpect(status().isCreated());

        // Widening the role takes effect at once; removing it restores full member rights.
        perform(put("/api/projects/" + key + "/roles/" + roleId), owner, j(Map.of("name", "QA",
                "permissions", List.of("COMMENT", "LOG_TIME", "CREATE_TASKS")))).andExpect(status().isOk());
        task(qa, key, "Found by QA");
        perform(put("/api/projects/" + key + "/members/" + qa.id() + "/custom-role"), owner, "{\"roleId\":null}").andExpect(status().isNoContent());
        perform(post("/api/projects/" + key + "/epics"), qa, body("name", "Now allowed")).andExpect(status().isCreated());

        // Deleting a role frees its members.
        perform(put("/api/projects/" + key + "/members/" + dev.id() + "/custom-role"), owner, j(Map.of("roleId", roleId)));
        perform(delete("/api/projects/" + key + "/roles/" + roleId), owner).andExpect(status().isNoContent());
        perform(post("/api/projects/" + key + "/epics"), dev, body("name", "Free again")).andExpect(status().isCreated());
        perform(put("/api/projects/" + key + "/members/" + owner.id() + "/custom-role"), owner, j(Map.of("roleId", 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void internalCommentsAreHiddenFromGuestsViewersAndLimitedRoles() throws Exception {
        Account owner = register();
        Account member = register();
        Account guest = register();
        Account viewer = register();
        Account limited = register();
        String key = project(owner, member, guest, viewer, limited);
        perform(put("/api/projects/" + key + "/members/" + guest.id() + "/role"), owner, body("role", "GUEST")).andExpect(status().isOk());
        perform(put("/api/projects/" + key + "/members/" + viewer.id() + "/role"), owner, body("role", "VIEWER")).andExpect(status().isOk());
        long roleId = read(perform(post("/api/projects/" + key + "/roles"), owner, j(Map.of("name", "Contractor",
                "permissions", List.of("COMMENT", "EDIT_TASKS"))))).get("id").asLong();
        perform(put("/api/projects/" + key + "/members/" + limited.id() + "/custom-role"), owner, j(Map.of("roleId", roleId)));

        long taskId = task(owner, key, "Refund request", "assigneeId", member.id()).get("id").asLong();
        perform(put("/api/tasks/" + taskId + "/watch"), guest);
        perform(post("/api/tasks/" + taskId + "/comments"), owner, body("body", "Public update for everyone")).andExpect(status().isCreated());
        JsonNode secret = read(perform(post("/api/tasks/" + taskId + "/comments"), owner,
                j(Map.of("body", "Internal: the customer is on a legacy plan zebracorn @" + guest.username(), "internal", true)))
                .andExpect(status().isCreated()));
        assertThat(secret.get("internal").asBoolean()).isTrue();
        long secretId = secret.get("id").asLong();
        // Replies to internal comments stay internal.
        JsonNode reply = read(perform(post("/api/tasks/" + taskId + "/comments"), member,
                j(Map.of("body", "Noted", "parentId", secretId))).andExpect(status().isCreated()));
        assertThat(reply.get("internal").asBoolean()).isTrue();

        assertThat(texts(getJson("/api/tasks/" + taskId + "/comments", member), "body")).hasSize(3);
        for (Account outsider : List.of(guest, viewer, limited)) {
            List<String> seen = texts(getJson("/api/tasks/" + taskId + "/comments", outsider), "body");
            assertThat(seen).containsExactly("Public update for everyone");
            assertThat(read(perform(get("/api/search").param("q", "comment ~ zebracorn"), outsider).andExpect(status().isOk())).get("total").asInt())
                    .isZero();
            assertThat(getJson("/api/search/text?q=zebracorn", outsider).toString()).doesNotContain("zebracorn");
            perform(put("/api/tasks/" + taskId + "/comments/" + secretId + "/reactions"), outsider, body("emoji", "👍"))
                    .andExpect(status().isNotFound());
        }
        assertThat(read(perform(get("/api/search").param("q", "comment ~ zebracorn"), member).andExpect(status().isOk())).get("total").asInt())
                .isEqualTo(1);
        // The mentioned guest was not told about the internal comment.
        assertThat(getJson("/api/notifications", guest).toString()).doesNotContain("mentioned you");

        // Guests and limited roles cannot write internal comments.
        perform(post("/api/tasks/" + taskId + "/comments"), guest, j(Map.of("body", "sneaky", "internal", true))).andExpect(status().isForbidden());
        perform(post("/api/tasks/" + taskId + "/comments"), limited, j(Map.of("body", "sneaky", "internal", true))).andExpect(status().isForbidden());
        perform(post("/api/tasks/" + taskId + "/comments"), guest, body("body", "Any news?")).andExpect(status().isCreated());
    }
}
