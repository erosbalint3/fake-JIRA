package com.fakejira;

import com.fakejira.template.RecurringController;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Issue types, templates, recurring tasks, clone, move, comment replies/reactions and description history. */
@SpringBootTest
@AutoConfigureMockMvc
class TaskWorkflowTest extends ApiTestSupport {

    @Autowired
    private RecurringController recurring;

    @Test
    void issueTypesAreStoredFilteredAndBulkEdited() throws Exception {
        Account owner = register();
        String key = project(owner);
        JsonNode bug = task(owner, key, "Crash on save", "type", "BUG");
        JsonNode story = task(owner, key, "As a user…");
        assertThat(bug.get("type").asText()).isEqualTo("BUG");
        assertThat(story.get("type").asText()).isEqualTo("TASK");
        assertThat(texts(getJson("/api/tasks?project=" + key + "&type=BUG", owner), "key")).containsExactly(bug.get("key").asText());
        perform(post("/api/tasks/bulk"), owner, "{\"taskIds\":[" + story.get("id") + "],\"type\":\"STORY\"}")
                .andExpect(status().isOk());
        assertThat(getJson("/api/tasks/" + story.get("id"), owner).get("type").asText()).isEqualTo("STORY");
    }

    @Test
    void templatesPrefillAndChecklistsComeAlong() throws Exception {
        Account owner = register();
        Account viewer = register();
        String key = project(owner);
        perform(post("/api/projects/" + key + "/members"), owner, body("login", viewer.username(), "role", "VIEWER"));
        String template = "{\"name\":\"Bug report\",\"type\":\"BUG\",\"title\":\"Bug: \",\"description\":\"## Steps\",\"priority\":\"HIGH\","
                + "\"labels\":[\"bug\"],\"checklist\":[\"Reproduce\",\"Fix\",\"Test\"]}";
        perform(post("/api/projects/" + key + "/templates"), viewer, template).andExpect(status().isForbidden());
        JsonNode created = read(perform(post("/api/projects/" + key + "/templates"), owner, template).andExpect(status().isCreated()));
        assertThat(created.get("checklist").size()).isEqualTo(3);
        assertThat(getJson("/api/projects/" + key + "/templates", viewer).size()).isEqualTo(1);

        // The create form sends the template's values, checklist included.
        JsonNode task = task(owner, key, "Bug: login fails", "type", "BUG", "checklist", java.util.List.of("Reproduce", "Fix"));
        assertThat(task.get("checklistTotal").asInt()).isEqualTo(2);
        perform(delete("/api/templates/" + created.get("id").asLong()), owner).andExpect(status().isNoContent());
    }

    @Test
    void recurringTasksAreCreatedOnSchedule() throws Exception {
        Account owner = register();
        Account member = register();
        String key = project(owner, member);
        String body = "{\"title\":\"Weekly deploy review\",\"type\":\"TASK\",\"priority\":\"MEDIUM\",\"frequency\":\"DAILY\","
                + "\"checklist\":[\"Check dashboards\"],\"assigneeId\":" + member.id() + ",\"dueInDays\":2}";
        JsonNode rule = read(perform(post("/api/projects/" + key + "/recurring"), owner, body).andExpect(status().isCreated()));
        assertThat(rule.get("nextRun").asText()).isEqualTo(LocalDate.now().toString());

        recurring.runDue();
        JsonNode tasks = getJson("/api/tasks?project=" + key, owner);
        assertThat(tasks.size()).isEqualTo(1);
        JsonNode created = tasks.get(0);
        assertThat(created.get("title").asText()).isEqualTo("Weekly deploy review");
        assertThat(created.get("assignee").get("id").asLong()).isEqualTo(member.id());
        assertThat(created.get("checklistTotal").asInt()).isEqualTo(1);
        assertThat(created.get("dueDate").asText()).isEqualTo(LocalDate.now().plusDays(2).toString());
        // Running again the same day does nothing: the schedule moved on.
        recurring.runDue();
        assertThat(getJson("/api/tasks?project=" + key, owner).size()).isEqualTo(1);
        JsonNode after = getJson("/api/projects/" + key + "/recurring", owner).get(0);
        assertThat(after.get("nextRun").asText()).isEqualTo(LocalDate.now().plusDays(1).toString());
        assertThat(after.get("lastTaskKey").asText()).isEqualTo(created.get("key").asText());

        perform(post("/api/recurring/" + rule.get("id").asLong() + "/run"), member).andExpect(status().isOk());
        assertThat(getJson("/api/tasks?project=" + key, owner).size()).isEqualTo(2);
    }

    @Test
    void tasksCanBeClonedAndMovedWithOldKeysStillWorking() throws Exception {
        Account owner = register();
        String from = project(owner);
        String to = project(owner);
        JsonNode parent = task(owner, from, "Epic chunk", "storyPoints", 5, "labels", java.util.List.of("api"));
        task(owner, from, "Sub one", "parentId", parent.get("id").asLong());

        JsonNode clone = read(perform(post("/api/tasks/" + parent.get("id").asLong() + "/clone"), owner, "{\"subtasks\":true}")
                .andExpect(status().isCreated()));
        assertThat(clone.get("title").asText()).isEqualTo("Copy of Epic chunk");
        assertThat(clone.get("storyPoints").asInt()).isEqualTo(5);
        assertThat(clone.get("subtaskTotal").asInt()).isEqualTo(1);

        String oldKey = parent.get("key").asText();
        JsonNode moved = read(perform(post("/api/tasks/" + parent.get("id").asLong() + "/move"), owner, body("projectKey", to))
                .andExpect(status().isOk()));
        assertThat(moved.get("key").asText()).startsWith(to + "-");
        assertThat(getJson("/api/tasks/key/" + oldKey, owner).get("key").asText()).isEqualTo(moved.get("key").asText());
        assertThat(getJson("/api/tasks/" + parent.get("id").asLong() + "/subtasks", owner).get(0).get("key").asText())
                .startsWith(to + "-");
        // A subtask cannot move on its own.
        long sub = getJson("/api/tasks/" + clone.get("id").asLong() + "/subtasks", owner).get(0).get("id").asLong();
        perform(post("/api/tasks/" + sub + "/move"), owner, body("projectKey", to)).andExpect(status().isBadRequest());
    }

    @Test
    void commentsHaveRepliesAndReactions() throws Exception {
        Account owner = register();
        Account member = register();
        String key = project(owner, member);
        long taskId = task(owner, key, "Discuss").get("id").asLong();
        long first = read(perform(post("/api/tasks/" + taskId + "/comments"), owner, body("body", "Idea?"))).get("id").asLong();
        long reply = read(perform(post("/api/tasks/" + taskId + "/comments"), member,
                "{\"body\":\"Yes!\",\"parentId\":" + first + "}")).get("parentId").asLong();
        assertThat(reply).isEqualTo(first);
        assertThat(texts(getJson("/api/notifications", owner).get("items"), "message").toString()).contains("replied to your comment");

        perform(put("/api/tasks/" + taskId + "/comments/" + first + "/reactions"), member, body("emoji", "👍"))
                .andExpect(jsonPath("$.reactions[0].count").value(1))
                .andExpect(jsonPath("$.reactions[0].mine").value(true));
        perform(put("/api/tasks/" + taskId + "/comments/" + first + "/reactions"), owner, body("emoji", "👍"))
                .andExpect(jsonPath("$.reactions[0].count").value(2));
        perform(put("/api/tasks/" + taskId + "/comments/" + first + "/reactions"), owner, body("emoji", "👍"))
                .andExpect(jsonPath("$.reactions[0].count").value(1));
        perform(put("/api/tasks/" + taskId + "/comments/" + first + "/reactions"), owner, body("emoji", "💩"))
                .andExpect(status().isBadRequest());

        // Deleting the thread start removes its replies; deleting the task removes everything.
        perform(delete("/api/tasks/" + taskId + "/comments/" + first), owner).andExpect(status().isNoContent());
        assertThat(getJson("/api/tasks/" + taskId + "/comments", owner).size()).isZero();
        perform(post("/api/tasks/" + taskId + "/comments"), owner, body("body", "again"));
        perform(delete("/api/tasks/" + taskId), owner).andExpect(status().isNoContent());
    }

    @Test
    void descriptionChangesKeepTheOldText() throws Exception {
        Account owner = register();
        String key = project(owner);
        long id = task(owner, key, "Doc", "description", "line one\nline two").get("id").asLong();
        perform(put("/api/tasks/" + id), owner, body("title", "Doc", "description", "line one\nline 2", "priority", "MEDIUM"))
                .andExpect(status().isOk());
        JsonNode change = null;
        for (JsonNode entry : getJson("/api/tasks/" + id + "/activity", owner)) {
            if (entry.get("message").asText().equals("updated the description")) {
                change = entry;
            }
        }
        assertThat(change).isNotNull();
        assertThat(change.get("before").asText()).isEqualTo("line one\nline two");
        assertThat(change.get("after").asText()).isEqualTo("line one\nline 2");
        perform(get("/api/tasks/" + id), owner).andExpect(jsonPath("$.type").value("TASK"));
    }
}
