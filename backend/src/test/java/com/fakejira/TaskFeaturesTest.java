package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;

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
class TaskFeaturesTest extends ApiTestSupport {

    @Test
    void assigningToAnotherMemberNotifiesThemAndIsLogged() throws Exception {
        Account alice = register();
        Account bob = register();
        Account outsider = register();
        String key = project(alice, bob);
        long id = task(alice, key, "Review PR").get("id").asLong();

        perform(put("/api/tasks/" + id + "/assignee"), alice, "{\"assigneeId\":" + outsider.id() + "}")
                .andExpect(status().isBadRequest());
        perform(put("/api/tasks/" + id + "/assignee"), alice, "{\"assigneeId\":" + bob.id() + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.id").value(bob.id()));
        perform(get("/api/notifications"), bob)
                .andExpect(jsonPath("$.items[0].message").value(containsString("assigned you to " + key + "-1")));

        perform(put("/api/tasks/" + id + "/assignee"), alice, "{\"assigneeId\":null}")
                .andExpect(jsonPath("$.assignee").doesNotExist());
        assertThat(texts(getJson("/api/tasks/" + id + "/activity", alice), "message"))
                .containsExactly("unassigned " + bob.username(), "assigned the task to " + bob.username(), "created the task");
    }

    @Test
    void labelsAndDueDatesAreNormalizedFilterableAndLogged() throws Exception {
        Account alice = register();
        String key = project(alice);
        LocalDate due = LocalDate.of(2030, 5, 17);
        JsonNode created = task(alice, key, "Label me", "labels", List.of(" Backend ", "UI Bug", "backend"),
                "dueDate", due.toString());
        long id = created.get("id").asLong();
        assertThat(created.get("labels").toString()).isEqualTo("[\"backend\",\"ui-bug\"]");
        assertThat(created.get("dueDate").asText()).isEqualTo("2030-05-17");

        task(alice, key, "Other");
        assertThat(ids(getJson("/api/tasks?project=" + key + "&label=UI-bug", alice))).containsExactly(id);
        assertThat(getJson("/api/projects/" + key + "/labels", alice).toString()).isEqualTo("[\"backend\",\"ui-bug\"]");

        perform(put("/api/tasks/" + id), alice, json.writeValueAsString(java.util.Map.of(
                "title", "Label me", "description", "", "priority", "CRITICAL", "labels", List.of("backend"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").doesNotExist());
        assertThat(texts(getJson("/api/tasks/" + id + "/activity", alice), "message")).contains(
                "changed priority from Medium to Critical", "removed the due date", "set labels to backend");
    }

    @Test
    void checklistTracksProgressOnTheTask() throws Exception {
        Account alice = register();
        Account outsider = register();
        String key = project(alice);
        long id = task(alice, key, "With steps").get("id").asLong();

        long first = read(perform(post("/api/tasks/" + id + "/checklist"), alice, body("text", "Write code"))
                .andExpect(status().isCreated())).get("id").asLong();
        perform(post("/api/tasks/" + id + "/checklist"), alice, body("text", "Write tests")).andExpect(status().isCreated());
        perform(post("/api/tasks/" + id + "/checklist"), outsider, body("text", "nope")).andExpect(status().isNotFound());

        perform(patch("/api/tasks/" + id + "/checklist/" + first), alice, "{\"done\":true}")
                .andExpect(jsonPath("$.done").value(true));
        perform(get("/api/tasks/" + id), alice)
                .andExpect(jsonPath("$.checklistTotal").value(2))
                .andExpect(jsonPath("$.checklistDone").value(1));
        assertThat(getJson("/api/tasks?project=" + key, alice).get(0).get("checklistDone").asInt()).isEqualTo(1);

        perform(delete("/api/tasks/" + id + "/checklist/" + first), alice).andExpect(status().isNoContent());
        perform(get("/api/tasks/" + id + "/checklist"), alice).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void mentionsNotifyMentionedMembersOnce() throws Exception {
        Account alice = register();
        Account bob = register();
        Account carol = register();
        Account outsider = register();
        String key = project(alice, bob, carol);
        long id = task(alice, key, "Discuss").get("id").asLong();
        perform(post("/api/notifications/read-all"), bob);
        perform(post("/api/notifications/read-all"), carol);

        perform(post("/api/tasks/" + id + "/comments"), alice,
                body("body", "@" + bob.username() + " and @" + carol.username().toUpperCase() + ", thoughts? cc @"
                        + outsider.username() + " mail: x@" + bob.username()))
                .andExpect(status().isCreated());

        perform(get("/api/notifications"), bob)
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[0].message").value(containsString("mentioned you in")));
        perform(get("/api/notifications"), carol).andExpect(jsonPath("$.unread").value(1));
        perform(get("/api/notifications"), outsider).andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void sprintLifecycleAndBurndown() throws Exception {
        Account alice = register();
        String key = project(alice);
        JsonNode sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, body("goal", "Ship v1"))
                .andExpect(status().isCreated()));
        long sprintId = sprint.get("id").asLong();
        assertThat(sprint.get("name").asText()).isEqualTo(key + " Sprint 1");
        assertThat(sprint.get("state").asText()).isEqualTo("PLANNED");

        long done = task(alice, key, "done one", "sprintId", sprintId).get("id").asLong();
        long open = task(alice, key, "open one").get("id").asLong();
        long backlog = task(alice, key, "stays in backlog").get("id").asLong();
        perform(put("/api/tasks/" + open + "/sprint"), alice, "{\"sprintId\":" + sprintId + "}")
                .andExpect(jsonPath("$.sprint.id").value(sprintId));
        assertThat(ids(getJson("/api/tasks?project=" + key + "&sprint=backlog", alice))).containsExactly(backlog);
        assertThat(ids(getJson("/api/tasks?project=" + key + "&sprint=" + sprintId, alice))).contains(done, open);

        perform(get("/api/sprints/" + sprintId + "/burndown"), alice).andExpect(status().isBadRequest());
        LocalDate today = LocalDate.now();
        perform(post("/api/sprints/" + sprintId + "/start"), alice, "{}")
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.startDate").value(today.toString()))
                .andExpect(jsonPath("$.endDate").value(today.plusDays(14).toString()));

        // Only one active sprint per project.
        long second = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        perform(post("/api/sprints/" + second + "/start"), alice, "{}").andExpect(status().isConflict());

        perform(patch("/api/tasks/" + done + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        JsonNode burndown = getJson("/api/sprints/" + sprintId + "/burndown", alice);
        assertThat(burndown.get("total").asInt()).isEqualTo(2);
        assertThat(burndown.get("done").asInt()).isEqualTo(1);
        assertThat(burndown.get("points")).hasSize(15);
        assertThat(burndown.get("points").get(0).get("remaining").asInt()).isEqualTo(1);
        assertThat(burndown.get("points").get(0).get("ideal").asDouble()).isEqualTo(2.0);
        assertThat(burndown.get("points").get(14).get("remaining").isNull()).isTrue();

        // Completing sends unfinished work back to the backlog but keeps it in the burndown total.
        perform(post("/api/sprints/" + sprintId + "/complete"), alice)
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.carriedOver").value(1));
        assertThat(ids(getJson("/api/tasks?project=" + key + "&sprint=backlog", alice))).contains(open, backlog);
        assertThat(getJson("/api/sprints/" + sprintId + "/burndown", alice).get("total").asInt()).isEqualTo(2);
        perform(put("/api/tasks/" + open + "/sprint"), alice, "{\"sprintId\":" + sprintId + "}")
                .andExpect(status().isBadRequest());

        // Planned sprints can be deleted; their tasks return to the backlog.
        perform(put("/api/tasks/" + open + "/sprint"), alice, "{\"sprintId\":" + second + "}").andExpect(status().isOk());
        perform(delete("/api/sprints/" + second), alice).andExpect(status().isNoContent());
        perform(get("/api/tasks/" + open), alice).andExpect(jsonPath("$.sprint").doesNotExist());
    }

    @Test
    void sprintsFromAnotherProjectAreRejected() throws Exception {
        Account alice = register();
        String a = project(alice);
        String b = project(alice);
        long sprintB = read(perform(post("/api/projects/" + b + "/sprints"), alice, "{}")).get("id").asLong();
        perform(post("/api/tasks"), alice, json.writeValueAsString(java.util.Map.of(
                "projectKey", a, "title", "x", "priority", "LOW", "sprintId", sprintB)))
                .andExpect(status().isBadRequest());
    }
}
