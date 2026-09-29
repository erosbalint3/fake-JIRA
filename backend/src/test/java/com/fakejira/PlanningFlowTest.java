package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Scope changes, flow reports, Kanban mode, retros, sprint review, planning poker, releases and epic dependencies. */
@SpringBootTest
@AutoConfigureMockMvc
class PlanningFlowTest extends ApiTestSupport {

    @Test
    void scopeChangesShowUpInBurndownAndReview() throws Exception {
        Account alice = register();
        String key = project(alice);
        long sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        long a = task(alice, key, "Committed A", "storyPoints", 5, "sprintId", sprint).get("id").asLong();
        long b = task(alice, key, "Committed B", "storyPoints", 3, "sprintId", sprint).get("id").asLong();
        long late = task(alice, key, "Late addition", "storyPoints", 2).get("id").asLong();
        perform(post("/api/sprints/" + sprint + "/start"), alice, "{}").andExpect(status().isOk());

        // Added and removed after the start: logged as scope changes.
        perform(put("/api/tasks/" + late + "/sprint"), alice, "{\"sprintId\":" + sprint + "}").andExpect(status().isOk());
        perform(put("/api/tasks/" + b + "/sprint"), alice, "{\"sprintId\":null}").andExpect(status().isOk());
        perform(patch("/api/tasks/" + a + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());

        JsonNode burndown = getJson("/api/sprints/" + sprint + "/burndown", alice);
        assertThat(burndown.get("changes")).hasSize(2);
        assertThat(texts(burndown.get("changes"), "key")).hasSize(2);
        assertThat(burndown.get("changes").get(0).get("added").asBoolean()).isTrue();
        assertThat(burndown.get("changes").get(1).get("added").asBoolean()).isFalse();
        assertThat(burndown.get("changes").get(0).get("actor").asText()).isEqualTo(alice.username());

        JsonNode review = getJson("/api/sprints/" + sprint + "/review", alice);
        assertThat(texts(review.get("completed"), "title")).containsExactly("Committed A");
        assertThat(texts(review.get("unfinished"), "title")).containsExactly("Late addition");
        assertThat(texts(review.get("added"), "title")).containsExactly("Late addition");
        assertThat(texts(review.get("removed"), "title")).containsExactly("Committed B");
        assertThat(review.get("completedPoints").asInt()).isEqualTo(5);
        assertThat(review.get("committedPoints").asInt()).isEqualTo(8);
        assertThat(review.get("markdown").asText()).contains("## Completed").contains("Committed A");

        // Completing carries the unfinished task over; it still counts as unfinished work in the review.
        perform(post("/api/sprints/" + sprint + "/complete"), alice).andExpect(status().isOk());
        review = getJson("/api/sprints/" + sprint + "/review", alice);
        assertThat(texts(review.get("unfinished"), "title")).containsExactly("Late addition");
        assertThat(getJson("/api/sprints/" + sprint + "/burndown", alice).get("changes")).hasSize(2);
    }

    @Test
    void flowReportsFollowStatusHistory() throws Exception {
        Account alice = register();
        String key = project(alice);
        long a = task(alice, key, "Flowing", "storyPoints", 3).get("id").asLong();
        task(alice, key, "Waiting");
        perform(patch("/api/tasks/" + a + "/status"), alice, body("status", "IN_PROGRESS")).andExpect(status().isOk());
        perform(patch("/api/tasks/" + a + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());

        JsonNode flow = getJson("/api/projects/" + key + "/flow?days=14", alice);
        assertThat(flow).hasSize(14);
        JsonNode today = flow.get(13);
        assertThat(today.get("date").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(today.get("done").asInt()).isEqualTo(1);
        assertThat(today.get("todo").asInt()).isEqualTo(1);
        assertThat(flow.get(0).get("todo").asInt()).isZero();

        JsonNode cycle = getJson("/api/projects/" + key + "/cycle-time", alice);
        assertThat(cycle.get("count").asInt()).isEqualTo(1);
        assertThat(cycle.get("tasks").get(0).get("cycleDays").isNull()).isFalse();

        JsonNode throughput = getJson("/api/projects/" + key + "/throughput?weeks=4", alice);
        assertThat(throughput).hasSize(4);
        assertThat(throughput.get(3).get("tasks").asInt()).isEqualTo(1);
        assertThat(throughput.get(3).get("points").asInt()).isEqualTo(3);
    }

    @Test
    void kanbanProjectsHaveNoSprints() throws Exception {
        Account alice = register();
        String key = project(alice);
        long sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        perform(post("/api/sprints/" + sprint + "/start"), alice, "{}").andExpect(status().isOk());
        String kanban = "{\"name\":\"Board\",\"description\":\"\",\"kanban\":true,\"color\":\"#0e7c66\"}";
        perform(put("/api/projects/" + key), alice, kanban).andExpect(status().isBadRequest());
        perform(post("/api/sprints/" + sprint + "/complete"), alice).andExpect(status().isOk());
        perform(put("/api/projects/" + key), alice, kanban)
                .andExpect(jsonPath("$.kanban").value(true))
                .andExpect(jsonPath("$.color").value("#0e7c66"));
        perform(post("/api/projects/" + key + "/sprints"), alice, "{}").andExpect(status().isBadRequest());
        // Leaving out the mode keeps it.
        perform(put("/api/projects/" + key), alice, "{\"name\":\"Board 2\",\"description\":\"\"}")
                .andExpect(jsonPath("$.kanban").value(true));
    }

    @Test
    void retrospectivesCollectVoteAndCreateTasks() throws Exception {
        Account alice = register();
        Account bob = register();
        Account viewer = register();
        String key = project(alice, bob);
        perform(post("/api/projects/" + key + "/members"), alice, body("login", viewer.username(), "role", "VIEWER"));
        long sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        String card = "{\"kind\":\"WENT_WELL\",\"text\":\"Pairing on the parser\"}";
        perform(post("/api/sprints/" + sprint + "/retro"), alice, card).andExpect(status().isBadRequest());
        perform(post("/api/sprints/" + sprint + "/start"), alice, "{}");

        long good = read(perform(post("/api/sprints/" + sprint + "/retro"), alice, card).andExpect(status().isCreated()))
                .get("id").asLong();
        long action = read(perform(post("/api/sprints/" + sprint + "/retro"), bob,
                "{\"kind\":\"ACTION\",\"text\":\"Add a smoke test to CI\"}")).get("id").asLong();
        perform(post("/api/sprints/" + sprint + "/retro"), viewer, card).andExpect(status().isForbidden());

        perform(post("/api/retro/" + action + "/vote"), alice).andExpect(jsonPath("$.votes").value(1))
                .andExpect(jsonPath("$.voted").value(true));
        perform(post("/api/retro/" + action + "/vote"), bob).andExpect(jsonPath("$.votes").value(2));
        perform(post("/api/retro/" + action + "/vote"), bob).andExpect(jsonPath("$.votes").value(1));
        // Most votes first.
        JsonNode items = getJson("/api/sprints/" + sprint + "/retro", viewer);
        assertThat(items.get(0).get("id").asLong()).isEqualTo(action);
        assertThat(items.get(0).get("voted").asBoolean()).isFalse();

        // Only the author (or owner) edits.
        perform(put("/api/retro/" + good), bob, "{\"kind\":\"TO_IMPROVE\",\"text\":\"x\"}").andExpect(status().isForbidden());
        perform(put("/api/retro/" + action), alice, "{\"kind\":\"ACTION\",\"text\":\"Add a smoke test to CI pipeline\"}")
                .andExpect(status().isOk());

        JsonNode linked = read(perform(post("/api/retro/" + action + "/task"), bob).andExpect(status().isOk()));
        String taskKey = linked.get("taskKey").asText();
        assertThat(taskKey).startsWith(key + "-");
        JsonNode task = getJson("/api/tasks/" + linked.get("taskId").asLong(), alice);
        assertThat(task.get("title").asText()).isEqualTo("Add a smoke test to CI pipeline");
        assertThat(task.get("labels").get(0).asText()).isEqualTo("retro");
        perform(post("/api/retro/" + action + "/task"), bob).andExpect(status().isConflict());

        perform(delete("/api/retro/" + good), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/sprints/" + sprint + "/retro", alice)).hasSize(1);
    }

    @Test
    void planningPokerHidesVotesUntilRevealed() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long id = task(alice, key, "Estimate me").get("id").asLong();
        String url = "/api/tasks/" + id + "/poker";
        perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url), alice)
                .andExpect(jsonPath("$.active").value(false));
        perform(put(url + "/vote"), alice, body("value", "5")).andExpect(status().isBadRequest());

        perform(post(url), alice).andExpect(jsonPath("$.active").value(true));
        perform(put(url + "/vote"), alice, body("value", "4")).andExpect(status().isBadRequest());
        perform(put(url + "/vote"), alice, body("value", "5")).andExpect(jsonPath("$.myVote").value("5"));
        perform(put(url + "/vote"), bob, body("value", "8")).andExpect(status().isOk());

        // Bob sees that Alice voted, but not what.
        JsonNode state = getJson(url, bob);
        assertThat(state.get("votes")).hasSize(2);
        for (JsonNode vote : state.get("votes")) {
            if (vote.get("user").get("id").asLong() == alice.id()) {
                assertThat(vote.get("value").isNull()).isTrue();
            } else {
                assertThat(vote.get("value").asText()).isEqualTo("8");
            }
        }

        state = read(perform(post(url + "/reveal"), alice).andExpect(status().isOk()));
        assertThat(state.get("average").asDouble()).isEqualTo(6.5);
        assertThat(state.get("suggestion").asText()).isEqualTo("8");
        assertThat(state.get("consensus").asBoolean()).isFalse();
        perform(put(url + "/vote"), bob, body("value", "5")).andExpect(status().isBadRequest());

        // Restart clears the votes.
        perform(post(url), bob).andExpect(jsonPath("$.votes.length()").value(0)).andExpect(jsonPath("$.revealed").value(false));
        perform(put(url + "/vote"), alice, body("value", "3"));
        perform(put(url + "/vote"), bob, body("value", "3"));
        perform(post(url + "/reveal"), bob).andExpect(jsonPath("$.consensus").value(true));
        perform(post(url + "/accept"), alice, "{\"points\":3}").andExpect(jsonPath("$.active").value(false));
        assertThat(getJson("/api/tasks/" + id, alice).get("storyPoints").asInt()).isEqualTo(3);
    }

    @Test
    void releasesGroupTasksAndProduceNotes() throws Exception {
        Account alice = register();
        String key = project(alice);
        String releases = "/api/projects/" + key + "/releases";
        long v1 = read(perform(post(releases), alice, "{\"name\":\"1.0\",\"description\":\"First cut\"}")
                .andExpect(status().isCreated())).get("id").asLong();
        long v2 = read(perform(post(releases), alice, "{\"name\":\"1.1\"}")).get("id").asLong();
        perform(post(releases), alice, "{\"name\":\"1.0\"}").andExpect(status().isConflict());

        long story = task(alice, key, "Dark mode", "type", "STORY").get("id").asLong();
        long bug = task(alice, key, "Crash on save", "type", "BUG").get("id").asLong();
        long open = task(alice, key, "Half done").get("id").asLong();
        for (long id : List.of(story, bug, open)) {
            perform(put("/api/tasks/" + id + "/release"), alice, "{\"releaseId\":" + v1 + "}")
                    .andExpect(jsonPath("$.release.name").value("1.0"));
        }
        perform(patch("/api/tasks/" + story + "/status"), alice, body("status", "DONE"));
        perform(patch("/api/tasks/" + bug + "/status"), alice, body("status", "DONE"));

        JsonNode list = getJson(releases, alice);
        assertThat(list.get(0).get("taskCount").asInt()).isEqualTo(3);
        assertThat(list.get(0).get("doneCount").asInt()).isEqualTo(2);

        String notes = getJson("/api/releases/" + v1 + "/notes", alice).get("markdown").asText();
        assertThat(notes).contains("## New features").contains("Dark mode").contains("## Bug fixes")
                .contains("Crash on save").contains("## Not finished yet").contains("Half done");

        JsonNode shipped = read(perform(post("/api/releases/" + v1 + "/release"), alice, "{\"moveUnfinishedTo\":" + v2 + "}")
                .andExpect(status().isOk()));
        assertThat(shipped.get("released").asBoolean()).isTrue();
        assertThat(shipped.get("taskCount").asInt()).isEqualTo(2);
        assertThat(getJson("/api/tasks/" + open, alice).get("release").get("name").asText()).isEqualTo("1.1");
        perform(post("/api/releases/" + v1 + "/release"), alice).andExpect(status().isBadRequest());

        perform(delete("/api/releases/" + v2), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/tasks/" + open, alice).get("release").isNull()).isTrue();
    }

    @Test
    void epicDependenciesRejectCycles() throws Exception {
        Account alice = register();
        String key = project(alice);
        String epics = "/api/projects/" + key + "/epics";
        long a = read(perform(post(epics), alice, body("name", "Auth"))).get("id").asLong();
        long b = read(perform(post(epics), alice, body("name", "Billing"))).get("id").asLong();
        long c = read(perform(post(epics), alice, body("name", "Reports"))).get("id").asLong();

        perform(put("/api/epics/" + b + "/dependencies"), alice, json.writeValueAsString(Map.of("dependsOn", List.of(a))))
                .andExpect(jsonPath("$.dependsOn[0]").value(a));
        perform(put("/api/epics/" + c + "/dependencies"), alice, json.writeValueAsString(Map.of("dependsOn", List.of(b))))
                .andExpect(status().isOk());
        perform(put("/api/epics/" + a + "/dependencies"), alice, json.writeValueAsString(Map.of("dependsOn", List.of(c))))
                .andExpect(status().isBadRequest());
        perform(put("/api/epics/" + a + "/dependencies"), alice, json.writeValueAsString(Map.of("dependsOn", List.of(a))))
                .andExpect(status().isBadRequest());

        // Deleting an epic drops it from others' dependencies; deleting the project cleans up everything.
        perform(delete("/api/epics/" + b), alice).andExpect(status().isNoContent());
        JsonNode list = getJson(epics, alice);
        assertThat(list.get(1).get("dependsOn")).isEmpty();
        perform(put("/api/epics/" + c + "/dependencies"), alice, json.writeValueAsString(Map.of("dependsOn", List.of(a))));
        perform(delete("/api/projects/" + key), alice).andExpect(status().isNoContent());
    }
}
