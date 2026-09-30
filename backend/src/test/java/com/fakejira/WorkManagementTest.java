package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.ArrayList;
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

/** Workflows, resolutions, components, approvals, type checklists, archive, helpers, sprint goals, capacity,
 * scheduling, timeline, portfolio and goals. */
@SpringBootTest
@AutoConfigureMockMvc
class WorkManagementTest extends ApiTestSupport {

    private String j(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private long column(JsonNode workflow, String status) {
        for (JsonNode c : workflow.get("columns")) {
            if (c.get("status").asText().equals(status)) {
                return c.get("id").asLong();
            }
        }
        throw new AssertionError(status);
    }

    @Test
    void workflowRestrictsMovesAndRequiresFields() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        JsonNode wf = getJson("/api/projects/" + key + "/workflow", alice);
        assertThat(wf.get("columns")).hasSize(4);
        long todo = column(wf, "TODO");
        long doing = column(wf, "IN_PROGRESS");
        long review = column(wf, "IN_REVIEW");
        long done = column(wf, "DONE");
        List<Map<String, Object>> transitions = new ArrayList<>();
        transitions.add(Map.of("fromId", todo, "toId", doing));
        transitions.add(Map.of("fromId", doing, "toId", review));
        transitions.add(Map.of("fromId", review, "toId", done));
        java.util.HashMap<String, Object> any = new java.util.HashMap<>();
        any.put("fromId", null);
        any.put("toId", todo);
        transitions.add(any);
        String request = j(Map.of("restricted", true,
                "columns", List.of(Map.of("id", doing, "required", List.of("assignee")),
                        Map.of("id", done, "required", List.of("resolution"))),
                "transitions", transitions));
        perform(put("/api/projects/" + key + "/workflow"), alice, request).andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(true));

        long id = task(alice, key, "Workflowed").get("id").asLong();
        // Not an allowed transition.
        perform(patch("/api/tasks/" + id + "/column"), alice, j(Map.of("columnId", done)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("does not allow")));
        // Allowed, but In progress needs an assignee.
        perform(patch("/api/tasks/" + id + "/status"), alice, body("status", "IN_PROGRESS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.required").value("assignee"));
        perform(put("/api/tasks/" + id + "/assignee"), alice, j(Map.of("assigneeId", bob.id()))).andExpect(status().isOk());
        perform(patch("/api/tasks/" + id + "/column"), alice, j(Map.of("columnId", doing))).andExpect(status().isOk());
        perform(patch("/api/tasks/" + id + "/column"), alice, j(Map.of("columnId", review))).andExpect(status().isOk());
        perform(patch("/api/tasks/" + id + "/column"), alice, j(Map.of("columnId", done)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.required").value("resolution"));
        perform(patch("/api/tasks/" + id + "/column"), alice, j(Map.of("columnId", done, "resolution", "WONT_DO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.resolution").value("WONT_DO"));
        perform(put("/api/tasks/" + id + "/resolution"), alice, body("resolution", "DUPLICATE"))
                .andExpect(jsonPath("$.resolution").value("DUPLICATE"));
        assertThat(read(perform(get("/api/search").param("q", "project = " + key + " AND resolution = duplicate"), alice))
                .get("total").asInt()).isEqualTo(1);
        // Reopening (allowed from anywhere to To do) clears the resolution.
        perform(patch("/api/tasks/" + id + "/status"), alice, body("status", "TODO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolution").isEmpty());
        // Unrestricted again: any move works.
        perform(put("/api/projects/" + key + "/workflow"), alice, j(Map.of("restricted", false, "transitions", List.of())))
                .andExpect(status().isOk());
        perform(patch("/api/tasks/" + id + "/column"), alice, j(Map.of("columnId", review))).andExpect(status().isOk());
        // Viewers may not change the workflow; bad requirements are refused.
        perform(put("/api/projects/" + key + "/workflow"), alice,
                j(Map.of("restricted", false, "columns", List.of(Map.of("id", todo, "required", List.of("resolution"))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void componentsAssignTheirLead() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long payments = read(perform(post("/api/projects/" + key + "/components"), alice,
                j(Map.of("name", "Payments", "description", "Checkout and billing", "leadId", bob.id()))))
                .get("id").asLong();
        perform(post("/api/projects/" + key + "/components"), alice, j(Map.of("name", "payments")))
                .andExpect(status().isBadRequest());
        JsonNode t = task(alice, key, "Card declined", "componentIds", List.of(payments));
        assertThat(t.get("assignee").get("id").asLong()).isEqualTo(bob.id());
        assertThat(texts(t.get("components"), "name")).containsExactly("Payments");
        long other = task(alice, key, "Unrelated").get("id").asLong();
        perform(put("/api/tasks/" + other + "/components"), alice, j(Map.of("ids", List.of(payments))))
                .andExpect(jsonPath("$.components[0].name").value("Payments"));
        assertThat(read(perform(get("/api/search").param("q", "component = payments AND project = " + key), alice))
                .get("total").asInt()).isEqualTo(2);
        assertThat(getJson("/api/projects/" + key + "/components", bob).get(0).get("taskCount").asInt()).isEqualTo(2);
        perform(delete("/api/components/" + payments), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/tasks/" + other, alice).get("components")).isEmpty();
    }

    @Test
    void approvalsGateAColumn() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        JsonNode wf = getJson("/api/projects/" + key + "/workflow", alice);
        long done = column(wf, "DONE");
        perform(put("/api/projects/" + key + "/workflow"), alice,
                j(Map.of("restricted", false, "columns", List.of(Map.of("id", done, "required", List.of("approval"))))))
                .andExpect(status().isOk());
        long id = task(alice, key, "Ship it").get("id").asLong();
        perform(patch("/api/tasks/" + id + "/status"), alice, body("status", "DONE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.required").value("approval"));
        long approval = read(perform(post("/api/tasks/" + id + "/approvals"), alice,
                j(Map.of("approverId", bob.id(), "note", "Please check the copy")))).get("id").asLong();
        JsonNode mine = getJson("/api/approvals/mine", bob);
        assertThat(ids(mine)).contains(approval);
        perform(post("/api/approvals/" + approval + "/decision"), alice, j(Map.of("approve", true)))
                .andExpect(status().isForbidden());
        perform(post("/api/approvals/" + approval + "/decision"), bob, j(Map.of("approve", false, "note", "Typo")))
                .andExpect(jsonPath("$.state").value("REJECTED"));
        perform(patch("/api/tasks/" + id + "/status"), alice, body("status", "DONE")).andExpect(status().isBadRequest());
        // Asking again reopens it.
        perform(post("/api/tasks/" + id + "/approvals"), alice, j(Map.of("approverId", bob.id()))).andExpect(status().isCreated());
        perform(post("/api/approvals/" + approval + "/decision"), bob, j(Map.of("approve", true)))
                .andExpect(jsonPath("$.state").value("APPROVED"));
        perform(patch("/api/tasks/" + id + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        assertThat(getJson("/api/approvals/mine", bob)).isEmpty();
    }

    @Test
    void typeChecklistsArchiveAndHelpers() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        perform(put("/api/projects/" + key + "/type-checklists/BUG"), alice,
                j(Map.of("items", List.of("Repro steps", "Fix", "Test", " ")))).andExpect(status().isOk());
        assertThat(getJson("/api/projects/" + key + "/type-checklists", bob).get("BUG")).hasSize(3);
        long bug = task(alice, key, "Crash", "type", "BUG").get("id").asLong();
        assertThat(texts(getJson("/api/tasks/" + bug + "/checklist", alice), "text")).containsExactly("Repro steps", "Fix", "Test");
        long own = task(alice, key, "Crash 2", "type", "BUG", "checklist", List.of("Only this")).get("id").asLong();
        assertThat(getJson("/api/tasks/" + own + "/checklist", alice)).hasSize(1);
        assertThat(getJson("/api/tasks/" + task(alice, key, "Plain").get("id").asLong() + "/checklist", alice)).isEmpty();

        // Helpers: shown in "mine", notified, searchable.
        perform(put("/api/tasks/" + bug + "/helpers"), alice, j(Map.of("userIds", List.of(bob.id()))))
                .andExpect(jsonPath("$.helpers[0].username").value(bob.username()));
        assertThat(ids(getJson("/api/tasks?scope=MINE&project=" + key, bob))).containsExactly(bug);
        assertThat(read(perform(get("/api/search").param("q", "helper = " + bob.username()), alice)).get("total").asInt()).isEqualTo(1);
        assertThat(getJson("/api/notifications", bob).toString()).contains("help with");

        // Archive: hidden from lists and search unless asked for.
        perform(post("/api/tasks/" + own + "/archive"), alice).andExpect(jsonPath("$.archivedAt").isNotEmpty());
        assertThat(ids(getJson("/api/tasks?project=" + key, alice))).doesNotContain(own);
        assertThat(ids(getJson("/api/tasks?archived=only&project=" + key, alice))).containsExactly(own);
        assertThat(read(perform(get("/api/search").param("q", "project = " + key), alice)).get("total").asInt()).isEqualTo(2);
        assertThat(read(perform(get("/api/search").param("q", "project = " + key + " AND archived = true"), alice))
                .get("total").asInt()).isEqualTo(1);
        perform(delete("/api/tasks/" + own + "/archive"), alice).andExpect(jsonPath("$.archivedAt").isEmpty());
        perform(patch("/api/tasks/" + own + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        assertThat(read(perform(post("/api/tasks/archive-done").param("project", key).param("days", "0"), alice))
                .get("archived").asInt()).isEqualTo(1);
        // Leaving the project stops helping.
        perform(delete("/api/projects/" + key + "/members/" + bob.id()), bob).andExpect(status().is2xxSuccessful());
        assertThat(getJson("/api/tasks/" + bug, alice).get("helpers")).isEmpty();
    }

    @Test
    void sprintGoalsAndCapacity() throws Exception {
        Account alice = register();
        String key = project(alice);
        long sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        long g1 = read(perform(post("/api/sprints/" + sprint + "/goals"), alice, body("text", "Checkout works on mobile"))).get("id").asLong();
        perform(post("/api/sprints/" + sprint + "/goals"), alice, body("text", "Docs updated")).andExpect(status().isCreated());
        perform(patch("/api/sprint-goals/" + g1), alice, j(Map.of("done", true))).andExpect(jsonPath("$.done").value(true));
        long id = task(alice, key, "Estimated", "sprintId", sprint, "assigneeId", alice.id()).get("id").asLong();
        perform(put("/api/tasks/" + id + "/schedule"), alice, j(Map.of("estimateMinutes", 600)))
                .andExpect(jsonPath("$.estimateMinutes").value(600));
        perform(post("/api/tasks/" + id + "/time"), alice, j(Map.of("minutes", 120))).andExpect(status().isCreated());
        perform(post("/api/sprints/" + sprint + "/start"), alice,
                j(Map.of("startDate", "2026-10-05", "endDate", "2026-10-16"))).andExpect(status().isOk());
        JsonNode cap = getJson("/api/sprints/" + sprint + "/capacity", alice);
        assertThat(cap.get("workingDays").asInt()).isEqualTo(10);
        JsonNode me = cap.get("people").get(0);
        assertThat(me.get("availableHours").decimalValue()).isEqualByComparingTo("60.0");
        assertThat(me.get("remainingHours").decimalValue()).isEqualByComparingTo("8.0");
        cap = read(perform(put("/api/sprints/" + sprint + "/capacity/" + alice.id()), alice,
                j(Map.of("hoursPerDay", 1, "daysOff", 3))));
        me = cap.get("people").get(0);
        assertThat(me.get("availableHours").decimalValue()).isEqualByComparingTo("7.0");
        assertThat(me.get("over").asBoolean()).isTrue();
        JsonNode review = getJson("/api/sprints/" + sprint + "/review", alice);
        assertThat(review.get("goals")).hasSize(2);
        assertThat(review.get("goalsMet").asInt()).isEqualTo(1);
        assertThat(review.get("markdown").asText()).contains("Goals met:** 1 of 2");
    }

    @Test
    void autoScheduleTimelineAndPortfolio() throws Exception {
        Account alice = register();
        String key = project(alice);
        perform(put("/api/projects/" + key), alice, j(Map.of("name", "Scheduled", "autoSchedule", true)))
                .andExpect(jsonPath("$.autoSchedule").value(true));
        LocalDate d = LocalDate.of(2026, 11, 2);
        long a = task(alice, key, "Design").get("id").asLong();
        long b = task(alice, key, "Build").get("id").asLong();
        long c = task(alice, key, "Launch").get("id").asLong();
        perform(put("/api/tasks/" + a + "/schedule"), alice, j(Map.of("startDate", d.toString(), "dueDate", d.plusDays(2).toString())));
        perform(put("/api/tasks/" + b + "/schedule"), alice, j(Map.of("startDate", d.plusDays(3).toString(), "dueDate", d.plusDays(6).toString())));
        perform(put("/api/tasks/" + c + "/schedule"), alice, j(Map.of("startDate", d.plusDays(3).toString(), "dueDate", d.plusDays(3).toString())));
        perform(put("/api/tasks/" + a + "/schedule"), alice, j(Map.of("startDate", d.toString(), "dueDate", d.minusDays(1).toString())))
                .andExpect(status().isBadRequest());
        perform(post("/api/tasks/" + a + "/links"), alice, body("type", "BLOCKS", "targetKey", key + "-2")).andExpect(status().isCreated());
        perform(post("/api/tasks/" + a + "/links"), alice, body("type", "BLOCKS", "targetKey", key + "-3")).andExpect(status().isCreated());

        JsonNode timeline = getJson("/api/projects/" + key + "/timeline", alice);
        assertThat(timeline.get("dependencies")).hasSize(2);
        List<Long> path = new ArrayList<>();
        timeline.get("criticalPath").forEach(n -> path.add(n.asLong()));
        assertThat(path).containsExactly(a, b);
        assertThat(timeline.get("criticalDays").asInt()).isEqualTo(7);

        // Design slips by 2 days: both blocked tasks move later, keeping their length.
        perform(put("/api/tasks/" + a + "/schedule"), alice, j(Map.of("startDate", d.toString(), "dueDate", d.plusDays(4).toString())))
                .andExpect(status().isOk());
        JsonNode build = getJson("/api/tasks/" + b, alice);
        assertThat(build.get("startDate").asText()).isEqualTo(d.plusDays(5).toString());
        assertThat(build.get("dueDate").asText()).isEqualTo(d.plusDays(8).toString());
        assertThat(getJson("/api/tasks/" + c, alice).get("startDate").asText()).isEqualTo(d.plusDays(5).toString());
        assertThat(getJson("/api/tasks/" + b + "/activity", alice).toString()).contains("because " + key + "-1 slipped");
        // Through the normal edit form too.
        perform(put("/api/tasks/" + a), alice, j(Map.of("title", "Design", "priority", "MEDIUM",
                "dueDate", d.plusDays(5).toString(), "labels", List.of()))).andExpect(status().isOk());
        assertThat(getJson("/api/tasks/" + b, alice).get("startDate").asText()).isEqualTo(d.plusDays(6).toString());

        JsonNode portfolio = getJson("/api/portfolio", alice);
        JsonNode row = null;
        for (JsonNode r : portfolio) {
            if (r.get("key").asText().equals(key)) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("open").asInt()).isEqualTo(3);
        assertThat(row.get("risk").asText()).isIn("OK", "WATCH", "AT_RISK");
    }

    @Test
    void goalsTrackEpicsAndNumbers() throws Exception {
        Account alice = register();
        Account outsider = register();
        String key = project(alice);
        long epic = read(perform(post("/api/projects/" + key + "/epics"), alice, body("name", "Checkout"))).get("id").asLong();
        long t1 = task(alice, key, "Cart", "epicId", epic).get("id").asLong();
        task(alice, key, "Pay", "epicId", epic);
        perform(patch("/api/tasks/" + t1 + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());

        long goal = read(perform(post("/api/goals"), alice, j(Map.of("title", "Faster checkout", "quarter", "2026-Q4"))))
                .get("id").asLong();
        perform(post("/api/goals"), alice, j(Map.of("title", "Bad", "quarter", "2026-Q5"))).andExpect(status().isBadRequest());
        perform(post("/api/goals/" + goal + "/key-results"), alice,
                j(Map.of("title", "Ship the new checkout", "kind", "EPICS", "epicIds", List.of(epic))))
                .andExpect(jsonPath("$.keyResults[0].percent").value(50));
        JsonNode g = read(perform(post("/api/goals/" + goal + "/key-results"), alice,
                j(Map.of("title", "Conversion", "kind", "MANUAL", "startValue", 2, "target", 4, "current", 2.5, "unit", "%"))));
        assertThat(g.get("keyResults").get(1).get("percent").asInt()).isEqualTo(25);
        assertThat(g.get("percent").asInt()).isEqualTo(38);
        // Epic links from projects you are not in are refused; private goals are invisible to outsiders.
        String other = project(outsider);
        long foreign = read(perform(post("/api/projects/" + other + "/epics"), outsider, body("name", "Theirs"))).get("id").asLong();
        perform(post("/api/goals/" + goal + "/key-results"), alice,
                j(Map.of("title", "Theirs", "kind", "EPICS", "epicIds", List.of(foreign)))).andExpect(status().isBadRequest());
        perform(get("/api/goals/" + goal), outsider).andExpect(status().isNotFound());
        perform(put("/api/goals/" + goal), alice, j(Map.of("title", "Faster checkout", "quarter", "2026-Q4", "shared", true)));
        perform(get("/api/goals/" + goal), outsider).andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.keyResults[0].epics[0].name").value("Epic in another project"));
        perform(put("/api/goals/" + goal), outsider, j(Map.of("title", "Mine now", "quarter", "2026-Q4")))
                .andExpect(status().isForbidden());
        assertThat(getJson("/api/epics/" + epic + "/goals", alice)).hasSize(1);
        // Deleting the epic removes it from the key result.
        perform(delete("/api/epics/" + epic), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/goals/" + goal, alice).get("keyResults").get(0).get("epics")).isEmpty();
    }
}
