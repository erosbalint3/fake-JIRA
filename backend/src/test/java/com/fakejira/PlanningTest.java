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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlanningTest extends ApiTestSupport {

    @Test
    void storyPointsDriveBurndownAndVelocity() throws Exception {
        Account alice = register();
        String key = project(alice);
        long sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        long a = task(alice, key, "A", "storyPoints", 5, "sprintId", sprint).get("id").asLong();
        long b = task(alice, key, "B", "storyPoints", 3, "sprintId", sprint).get("id").asLong();
        task(alice, key, "C", "sprintId", sprint);
        perform(post("/api/tasks"), alice, json.writeValueAsString(Map.of("projectKey", key, "title", "x",
                "priority", "LOW", "storyPoints", 101))).andExpect(status().isBadRequest());

        perform(post("/api/sprints/" + sprint + "/start"), alice, "{}").andExpect(status().isOk());
        perform(patch("/api/tasks/" + a + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        JsonNode burndown = getJson("/api/sprints/" + sprint + "/burndown", alice);
        assertThat(burndown.get("totalPoints").asInt()).isEqualTo(8);
        assertThat(burndown.get("donePoints").asInt()).isEqualTo(5);
        assertThat(burndown.get("points").get(0).get("remainingPoints").asInt()).isEqualTo(3);
        assertThat(burndown.get("points").get(0).get("idealPoints").asDouble()).isEqualTo(8.0);

        perform(post("/api/sprints/" + sprint + "/complete"), alice)
                .andExpect(jsonPath("$.carriedOverPoints").value(3));
        JsonNode velocity = getJson("/api/projects/" + key + "/velocity", alice);
        assertThat(velocity).hasSize(1);
        assertThat(velocity.get(0).get("committedPoints").asInt()).isEqualTo(8);
        assertThat(velocity.get(0).get("completedPoints").asInt()).isEqualTo(5);
        assertThat(velocity.get(0).get("committedTasks").asInt()).isEqualTo(3);
        assertThat(velocity.get(0).get("completedTasks").asInt()).isEqualTo(1);
        assertThat(b).isPositive();
    }

    @Test
    void subtasksInheritContextAndAreDeletedWithParent() throws Exception {
        Account alice = register();
        String key = project(alice);
        long sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        long epic = read(perform(post("/api/projects/" + key + "/epics"), alice, body("name", "Launch"))).get("id").asLong();
        long parent = task(alice, key, "Parent", "sprintId", sprint, "epicId", epic).get("id").asLong();
        JsonNode sub = task(alice, key, "Child", "parentId", parent);
        assertThat(sub.get("parent").get("id").asLong()).isEqualTo(parent);
        assertThat(sub.get("sprint").get("id").asLong()).isEqualTo(sprint);
        assertThat(sub.get("epic").get("id").asLong()).isEqualTo(epic);

        perform(post("/api/tasks"), alice, json.writeValueAsString(Map.of("projectKey", key, "title", "Grandchild",
                "priority", "LOW", "parentId", sub.get("id").asLong()))).andExpect(status().isBadRequest());

        perform(patch("/api/tasks/" + sub.get("id").asLong() + "/status"), alice, body("status", "DONE"));
        perform(get("/api/tasks/" + parent), alice)
                .andExpect(jsonPath("$.subtaskTotal").value(1))
                .andExpect(jsonPath("$.subtaskDone").value(1));
        assertThat(ids(getJson("/api/tasks/" + parent + "/subtasks", alice))).containsExactly(sub.get("id").asLong());

        perform(delete("/api/tasks/" + parent), alice).andExpect(status().isNoContent());
        perform(get("/api/tasks/" + sub.get("id").asLong()), alice).andExpect(status().isNotFound());
    }

    @Test
    void linksShowBothDirectionsAndBlockedFlag() throws Exception {
        Account alice = register();
        String key = project(alice);
        JsonNode blocker = task(alice, key, "Blocker");
        long blocked = task(alice, key, "Blocked").get("id").asLong();

        long linkId = read(perform(post("/api/tasks/" + blocker.get("id").asLong() + "/links"), alice,
                body("type", "BLOCKS", "targetKey", blocker.get("key").asText().replace("-1", "-2").toLowerCase()))
                .andExpect(status().isCreated())).get("id").asLong();
        perform(post("/api/tasks/" + blocked + "/links"), alice, body("type", "BLOCKS", "targetKey", key + "-2"))
                .andExpect(status().isBadRequest());

        JsonNode fromBlocked = getJson("/api/tasks/" + blocked + "/links", alice);
        assertThat(fromBlocked.get(0).get("label").asText()).isEqualTo("is blocked by");
        assertThat(fromBlocked.get(0).get("task").get("key").asText()).isEqualTo(key + "-1");
        perform(get("/api/tasks/" + blocked), alice).andExpect(jsonPath("$.blocked").value(true));

        perform(patch("/api/tasks/" + blocker.get("id").asLong() + "/status"), alice, body("status", "DONE"));
        perform(get("/api/tasks/" + blocked), alice).andExpect(jsonPath("$.blocked").value(false));

        perform(delete("/api/tasks/" + blocked + "/links/" + linkId), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/tasks/" + blocked + "/links", alice)).isEmpty();
    }

    @Test
    void epicsTrackProgressAndCycleColors() throws Exception {
        Account alice = register();
        String key = project(alice);
        long epic = read(perform(post("/api/projects/" + key + "/epics"), alice,
                json.writeValueAsString(Map.of("name", "Checkout", "startDate", "2026-10-01", "dueDate", "2026-11-15"))))
                .get("id").asLong();
        perform(post("/api/projects/" + key + "/epics"), alice,
                json.writeValueAsString(Map.of("name", "Bad", "startDate", "2026-10-10", "dueDate", "2026-10-01")))
                .andExpect(status().isBadRequest());
        long t1 = task(alice, key, "one", "epicId", epic, "storyPoints", 2).get("id").asLong();
        task(alice, key, "two", "epicId", epic, "storyPoints", 3);
        perform(patch("/api/tasks/" + t1 + "/status"), alice, body("status", "DONE"));

        JsonNode epics = getJson("/api/projects/" + key + "/epics", alice);
        assertThat(epics.get(0).get("taskCount").asInt()).isEqualTo(2);
        assertThat(epics.get(0).get("doneCount").asInt()).isEqualTo(1);
        assertThat(epics.get(0).get("points").asInt()).isEqualTo(5);
        assertThat(epics.get(0).get("donePoints").asInt()).isEqualTo(2);
        assertThat(ids(getJson("/api/tasks?project=" + key + "&epic=" + epic, alice))).hasSize(2);

        for (int i = 0; i < 8; i++) {
            perform(post("/api/projects/" + key + "/epics"), alice, body("name", "E" + i));
        }
        JsonNode all = getJson("/api/projects/" + key + "/epics", alice);
        assertThat(all.get(8).get("colorIndex").asInt()).isEqualTo(0);

        perform(delete("/api/epics/" + epic), alice).andExpect(status().isNoContent());
        perform(get("/api/tasks/" + t1), alice).andExpect(jsonPath("$.epic").doesNotExist());
    }

    @Test
    void customColumnsMapToStatuses() throws Exception {
        Account alice = register();
        String key = project(alice);
        JsonNode columns = getJson("/api/projects/" + key + "/columns", alice);
        assertThat(texts(columns, "name")).containsExactly("To do", "In progress", "In review", "Done");

        columns = read(perform(post("/api/projects/" + key + "/columns"), alice,
                json.writeValueAsString(Map.of("name", "QA", "status", "IN_REVIEW", "wipLimit", 2))));
        assertThat(texts(columns, "name")).containsExactly("To do", "In progress", "In review", "QA", "Done");
        long qa = columns.get(3).get("id").asLong();
        long inReview = columns.get(2).get("id").asLong();
        long done = columns.get(4).get("id").asLong();

        long id = task(alice, key, "Card").get("id").asLong();
        perform(patch("/api/tasks/" + id + "/column"), alice, "{\"columnId\":" + qa + "}")
                .andExpect(jsonPath("$.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.columnId").value(qa));
        // Changing status elsewhere drops a column that no longer matches.
        perform(patch("/api/tasks/" + id + "/status"), alice, body("status", "DONE"))
                .andExpect(jsonPath("$.columnId").doesNotExist());

        perform(delete("/api/columns/" + done), alice).andExpect(status().isBadRequest());
        perform(post("/api/columns/" + qa + "/move").param("direction", "-1"), alice)
                .andExpect(jsonPath("$[2].name").value("QA"));
        perform(delete("/api/columns/" + inReview), alice)
                .andExpect(jsonPath("$.length()").value(4));
        perform(put("/api/columns/" + qa), alice, json.writeValueAsString(Map.of("name", "Testing", "status", "IN_REVIEW")))
                .andExpect(jsonPath("$[2].name").value("Testing"))
                .andExpect(jsonPath("$[2].wipLimit").doesNotExist());
    }

    @Test
    void bulkEditAppliesToEveryTaskAndChecksAccess() throws Exception {
        Account alice = register();
        Account bob = register();
        Account outsider = register();
        String key = project(alice, bob);
        long sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        long a = task(alice, key, "a", "labels", List.of("old")).get("id").asLong();
        long b = task(alice, key, "b").get("id").asLong();

        JsonNode updated = read(perform(post("/api/tasks/bulk"), alice, json.writeValueAsString(Map.of(
                "taskIds", List.of(a, b), "status", "IN_PROGRESS", "priority", "HIGH", "assigneeId", bob.id(),
                "sprintId", sprint, "addLabels", List.of("Bulk"), "removeLabels", List.of("old")))).andExpect(status().isOk()));
        for (JsonNode task : updated) {
            assertThat(task.get("status").asText()).isEqualTo("IN_PROGRESS");
            assertThat(task.get("priority").asText()).isEqualTo("HIGH");
            assertThat(task.get("assignee").get("id").asLong()).isEqualTo(bob.id());
            assertThat(task.get("sprint").get("id").asLong()).isEqualTo(sprint);
            assertThat(task.get("labels").toString()).isEqualTo("[\"bulk\"]");
        }
        perform(post("/api/tasks/bulk"), outsider, json.writeValueAsString(Map.of("taskIds", List.of(a))))
                .andExpect(status().isNotFound());
        perform(post("/api/tasks/bulk"), alice, json.writeValueAsString(Map.of("taskIds", List.of(a, b), "delete", true)))
                .andExpect(status().isOk());
        perform(get("/api/tasks/" + a), alice).andExpect(status().isNotFound());
    }
}
