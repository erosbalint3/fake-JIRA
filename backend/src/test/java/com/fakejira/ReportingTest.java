package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Forecasts, burn-up, aging WIP, bug trends, SLA, exports, trend charts, scheduled reports and health checks. */
@SpringBootTest
@AutoConfigureMockMvc
class ReportingTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String j(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private void finishedWeeksAgo(long id, int weeks) {
        jdbc.update("update tasks set status = 'DONE', resolution = 'DONE', completed_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(weeks * 7L, ChronoUnit.DAYS)), id);
    }

    @Test
    void monteCarloForecast() throws Exception {
        Account alice = register();
        String key = project(alice);
        JsonNode none = getJson("/api/projects/" + key + "/forecast", alice);
        assertThat(none.get("enoughData").asBoolean()).isFalse();

        // Two finished tasks a week for the past four weeks, eight still open.
        for (int week = 1; week <= 4; week++) {
            for (int i = 0; i < 2; i++) {
                finishedWeeksAgo(task(alice, key, "Done " + week + i).get("id").asLong(), week);
            }
        }
        for (int i = 0; i < 8; i++) {
            task(alice, key, "Open " + i);
        }
        JsonNode forecast = getJson("/api/projects/" + key + "/forecast", alice);
        assertThat(forecast.get("enoughData").asBoolean()).isTrue();
        assertThat(forecast.get("remaining").asInt()).isEqualTo(8);
        // A steady two a week means four weeks at every confidence level.
        for (JsonNode estimate : forecast.get("completion")) {
            assertThat(estimate.get("weeks").asInt()).isEqualTo(4);
        }
        JsonNode byDate = getJson("/api/projects/" + key + "/forecast?by=" + LocalDate.now().plusWeeks(2), alice);
        assertThat(byDate.get("targetProbability").asInt()).isZero();
        assertThat(byDate.get("byTarget").get(0).get("items").asInt()).isEqualTo(4);
        JsonNode items = getJson("/api/projects/" + key + "/forecast?items=2", alice);
        assertThat(items.get("completion").get(3).get("weeks").asInt()).isEqualTo(1);
        perform(get("/api/projects/" + key + "/forecast?release=999999"), alice).andExpect(status().isNotFound());
        perform(get("/api/projects/" + key + "/forecast"), register()).andExpect(status().isNotFound());
    }

    @Test
    void burnupAgingAndBugTrends() throws Exception {
        Account alice = register();
        String key = project(alice);
        long release = read(perform(post("/api/projects/" + key + "/releases"), alice, j(Map.of("name", "1.0")))
                .andExpect(status().isCreated())).get("id").asLong();
        long a = task(alice, key, "Scope A", "storyPoints", 3).get("id").asLong();
        long b = task(alice, key, "Scope B", "storyPoints", 5).get("id").asLong();
        for (long id : List.of(a, b)) {
            perform(put("/api/tasks/" + id + "/release"), alice, j(Map.of("releaseId", release))).andExpect(status().isOk());
        }
        perform(patch("/api/tasks/" + a + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        JsonNode burnup = getJson("/api/releases/" + release + "/burnup", alice);
        JsonNode last = burnup.get("days").get(burnup.get("days").size() - 1);
        assertThat(last.get("scope").asInt()).isEqualTo(2);
        assertThat(last.get("done").asInt()).isEqualTo(1);
        assertThat(last.get("scopePoints").asInt()).isEqualTo(8);
        assertThat(last.get("donePoints").asInt()).isEqualTo(3);

        // Aging: b has been in progress for ten days; finished tasks set the yardstick.
        perform(patch("/api/tasks/" + b + "/status"), alice, body("status", "IN_PROGRESS")).andExpect(status().isOk());
        jdbc.update("update status_changes set changed_at = ? where task_id = ? and to_status = 'IN_PROGRESS'",
                Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)), b);
        long quick = task(alice, key, "Quick").get("id").asLong();
        perform(patch("/api/tasks/" + quick + "/status"), alice, body("status", "IN_PROGRESS")).andExpect(status().isOk());
        perform(patch("/api/tasks/" + quick + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        JsonNode aging = getJson("/api/projects/" + key + "/aging-wip", alice);
        assertThat(aging.get("items")).hasSize(1);
        assertThat(aging.get("items").get(0).get("ageDays").asDouble()).isBetween(9.9, 10.1);
        assertThat(aging.get("items").get(0).get("level").asText()).isEqualTo("late");

        // Bugs: two created this week, one fixed.
        long bug1 = task(alice, key, "Crash", "type", "BUG", "priority", "HIGH").get("id").asLong();
        task(alice, key, "Typo", "type", "BUG");
        perform(patch("/api/tasks/" + bug1 + "/status"), alice, j(Map.of("status", "DONE", "resolution", "FIXED")))
                .andExpect(status().isOk());
        JsonNode bugs = getJson("/api/projects/" + key + "/bug-trends", alice);
        JsonNode thisWeek = bugs.get("weeks").get(bugs.get("weeks").size() - 1);
        assertThat(thisWeek.get("created").asInt()).isEqualTo(2);
        assertThat(thisWeek.get("resolved").asInt()).isEqualTo(1);
        assertThat(thisWeek.get("open").asInt()).isEqualTo(1);
        assertThat(bugs.get("resolutions").get("FIXED").asInt()).isEqualTo(1);
        assertThat(bugs.get("openByPriority").get("MEDIUM").asInt()).isEqualTo(1);
        assertThat(texts(bugs.get("oldestOpen"), "title")).containsExactly("Typo");
    }

    @Test
    void slaTargetsAndReport() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        perform(put("/api/projects/" + key + "/sla"), bob, j(Map.of("targets", List.of(Map.of("priority", "HIGH", "resolveHours", 4)))))
                .andExpect(status().isForbidden());
        perform(put("/api/projects/" + key + "/sla"), alice, j(Map.of("targets",
                List.of(Map.of("priority", "HIGH", "responseHours", 8, "resolveHours", 4))))).andExpect(status().isBadRequest());
        JsonNode targets = read(perform(put("/api/projects/" + key + "/sla"), alice, j(Map.of("targets",
                List.of(Map.of("priority", "HIGH", "responseHours", 1, "resolveHours", 24))))).andExpect(status().isOk()));
        assertThat(targets.get(1).get("resolveHours").asInt()).isEqualTo(24);

        long late = task(alice, key, "Late", "priority", "HIGH").get("id").asLong();
        long fast = task(alice, key, "Fast", "priority", "HIGH").get("id").asLong();
        long untracked = task(alice, key, "No target", "priority", "LOW").get("id").asLong();
        jdbc.update("update tasks set created_at = ? where id = ?", Timestamp.from(Instant.now().minus(30, ChronoUnit.HOURS)), late);
        perform(post("/api/tasks/" + fast + "/comments"), bob, body("body", "On it")).andExpect(status().isCreated());
        perform(patch("/api/tasks/" + fast + "/status"), bob, body("status", "DONE")).andExpect(status().isOk());

        JsonNode lateSla = getJson("/api/tasks/" + late + "/sla", alice);
        assertThat(lateSla.get("responseState").asText()).isEqualTo("breached");
        assertThat(lateSla.get("resolveState").asText()).isEqualTo("breached");
        JsonNode fastSla = getJson("/api/tasks/" + fast + "/sla", alice);
        assertThat(fastSla.get("responseState").asText()).isEqualTo("met");
        assertThat(fastSla.get("resolveState").asText()).isEqualTo("met");
        perform(get("/api/tasks/" + untracked + "/sla"), alice).andExpect(status().isNoContent());

        JsonNode report = getJson("/api/projects/" + key + "/sla-report", alice);
        JsonNode high = report.get("priorities").get(1);
        assertThat(high.get("tasks").asInt()).isEqualTo(2);
        assertThat(high.get("resolveMet").asInt()).isEqualTo(1);
        assertThat(high.get("resolveBreached").asInt()).isEqualTo(1);
        assertThat(report.get("resolveMetPercent").asInt()).isEqualTo(50);
        assertThat(report.get("attention").get(0).get("task").get("id").asLong()).isEqualTo(late);
    }

    @Test
    void exportsAndTrend() throws Exception {
        Account alice = register();
        String key = project(alice);
        task(alice, key, "Export <me> & you", "labels", List.of("ui"));
        task(alice, key, "Árvíztűrő tükörfúrógép");

        byte[] xlsx = perform(get("/api/search/export?q=project = " + key), alice).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String sheet = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(sheet).contains("Export &lt;me&gt; &amp; you").contains("Árvíztűrő").contains(">Key<");

        byte[] pdf = perform(get("/api/search/export?format=pdf&q=project = " + key), alice).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(text).startsWith("%PDF-1.4").contains("Export <me> & you".replace("(", "\\(")).endsWith("%%EOF\n");
        assertThat(text).contains("Árvíztürö");
        perform(get("/api/search/export?format=doc"), alice).andExpect(status().isBadRequest());

        byte[] reports = perform(get("/api/projects/" + key + "/reports/export"), alice).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(reports.length).isGreaterThan(500);
        byte[] reportsPdf = perform(get("/api/projects/" + key + "/reports/export?format=pdf"), alice)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(reportsPdf, StandardCharsets.ISO_8859_1)).contains("Bug trends");

        JsonNode trend = getJson("/api/search/trend?q=project = " + key, alice);
        assertThat(trend).hasSize(12);
        assertThat(trend.get(11).get("created").asInt()).isEqualTo(2);
        assertThat(getJson("/api/search/stats?groupBy=resolution&q=project = " + key, alice).get(0).get("label").asText())
                .isEqualTo("Unresolved");
    }

    @Test
    void scheduledReports() throws Exception {
        Account alice = register();
        String key = project(alice);
        task(alice, key, "Overdue thing", "dueDate", LocalDate.now().minusDays(2).toString());

        perform(post("/api/report-subscriptions"), alice, j(Map.of("kind", "filter", "target", "status = = nope")))
                .andExpect(status().isBadRequest());
        perform(post("/api/report-subscriptions"), alice, j(Map.of("kind", "project", "target", "NOPE")))
                .andExpect(status().isBadRequest());
        Map<String, Object> request = new HashMap<>();
        request.put("kind", "project");
        request.put("target", key.toLowerCase());
        request.put("frequency", "WEEKLY");
        request.put("weekday", 5);
        request.put("hour", 9);
        JsonNode created = read(perform(post("/api/report-subscriptions"), alice, j(request)).andExpect(status().isCreated()));
        assertThat(created.get("target").asText()).isEqualTo(key);
        assertThat(created.get("title").asText()).isEqualTo("Project " + key + " summary");
        assertThat(created.get("nextSendAt").asText()).isNotEmpty();
        long id = created.get("id").asLong();

        JsonNode preview = getJson("/api/report-subscriptions/" + id + "/preview", alice);
        assertThat(preview.get("subject").asText()).isEqualTo("[FakeJIRA] Project " + key + " summary");
        assertThat(preview.get("body").asText()).contains("Overdue: 1").contains("Overdue thing");
        // Mail is not configured in tests: sending returns the content without sending.
        JsonNode sent = read(perform(post("/api/report-subscriptions/" + id + "/send"), alice).andExpect(status().isOk()));
        assertThat(sent.get("sent").asBoolean()).isFalse();

        JsonNode filter = read(perform(post("/api/report-subscriptions"), alice,
                j(Map.of("kind", "filter", "target", "project = " + key, "frequency", "DAILY"))).andExpect(status().isCreated()));
        assertThat(getJson("/api/report-subscriptions/" + filter.get("id").asLong() + "/preview", alice).get("body").asText())
                .contains("1 task matches");

        long dashboard = getJson("/api/dashboards", alice).get(0).get("id").asLong();
        JsonNode dash = read(perform(post("/api/report-subscriptions"), alice,
                j(Map.of("kind", "dashboard", "target", String.valueOf(dashboard)))).andExpect(status().isCreated()));
        assertThat(getJson("/api/report-subscriptions/" + dash.get("id").asLong() + "/preview", alice).get("body").asText())
                .contains("Assigned to me");

        Account bob = register();
        perform(get("/api/report-subscriptions/" + id + "/preview"), bob).andExpect(status().isNotFound());
        perform(post("/api/report-subscriptions"), bob, j(Map.of("kind", "dashboard", "target", String.valueOf(dashboard))))
                .andExpect(status().isBadRequest());
        assertThat(getJson("/api/report-subscriptions", alice)).hasSize(3);
        perform(delete("/api/report-subscriptions/" + id), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/report-subscriptions", alice)).hasSize(2);
    }

    @Test
    void scheduleOccurrences() {
        com.fakejira.user.User user = new com.fakejira.user.User("s", "s@example.com", "x");
        user.setTimeZone("UTC");
        com.fakejira.report.ReportSubscription weekly = new com.fakejira.report.ReportSubscription(user, "filter", "");
        weekly.setSchedule("t", "WEEKLY", 1, 8);
        // Wednesday 2026-09-30 12:00 UTC → last Monday 2026-09-28 08:00.
        Instant now = Instant.parse("2026-09-30T12:00:00Z");
        assertThat(com.fakejira.report.ReportSubscriptionController.lastOccurrence(weekly, java.time.ZoneOffset.UTC, now).toInstant())
                .isEqualTo(Instant.parse("2026-09-28T08:00:00Z"));
        com.fakejira.report.ReportSubscription daily = new com.fakejira.report.ReportSubscription(user, "filter", "");
        daily.setSchedule("t", "DAILY", 1, 13);
        assertThat(com.fakejira.report.ReportSubscriptionController.lastOccurrence(daily, java.time.ZoneOffset.UTC, now).toInstant())
                .isEqualTo(Instant.parse("2026-09-29T13:00:00Z"));
        // Created just now: nothing is due until the next occurrence has passed.
        Instant later = Instant.now().plus(2, ChronoUnit.DAYS);
        assertThat(com.fakejira.report.ReportSubscriptionController.isDue(daily, Instant.now())).isFalse();
        assertThat(com.fakejira.report.ReportSubscriptionController.isDue(daily, later)).isTrue();
        daily.setLastSentAt(later);
        assertThat(com.fakejira.report.ReportSubscriptionController.isDue(daily, later.plusSeconds(60))).isFalse();
    }

    @Test
    void healthCheckIsAnonymous() throws Exception {
        Account alice = register();
        Account bob = register();
        Account viewer = register();
        String key = project(alice, bob, viewer);
        perform(put("/api/projects/" + key + "/members/" + viewer.id() + "/role"), alice, body("role", "VIEWER"))
                .andExpect(status().isOk());
        JsonNode check = read(perform(post("/api/projects/" + key + "/health-checks"), alice,
                j(Map.of("title", "Q3", "categories", List.of("Speed", "Fun")))).andExpect(status().isCreated()));
        long id = check.get("check").get("id").asLong();
        assertThat(check.get("resultsVisible").asBoolean()).isFalse();

        perform(put("/api/health-checks/" + id + "/votes"), alice, j(Map.of("votes",
                List.of(Map.of("category", "Speed", "score", 3, "trend", 1), Map.of("category", "Fun", "score", 2)))))
                .andExpect(status().isOk());
        // Bob has not voted yet: no results for him.
        JsonNode bobView = getJson("/api/health-checks/" + id, bob);
        assertThat(bobView.get("resultsVisible").asBoolean()).isFalse();
        assertThat(bobView.get("results")).isEmpty();
        assertThat(bobView.get("check").get("voters").asInt()).isEqualTo(1);

        perform(put("/api/health-checks/" + id + "/votes"), bob, j(Map.of("votes",
                List.of(Map.of("category", "Speed", "score", 1, "trend", -1))))).andExpect(status().isOk());
        perform(put("/api/health-checks/" + id + "/votes"), bob, j(Map.of("votes",
                List.of(Map.of("category", "Nope", "score", 1))))).andExpect(status().isBadRequest());
        perform(put("/api/health-checks/" + id + "/votes"), viewer, j(Map.of("votes",
                List.of(Map.of("category", "Speed", "score", 1))))).andExpect(status().isForbidden());
        JsonNode results = getJson("/api/health-checks/" + id, bob);
        JsonNode speed = results.get("results").get(0);
        assertThat(speed.get("green").asInt()).isEqualTo(1);
        assertThat(speed.get("red").asInt()).isEqualTo(1);
        assertThat(speed.get("average").asDouble()).isEqualTo(2.0);
        // Only your own ratings come back, never anyone else's.
        assertThat(results.get("mine")).hasSize(1);
        assertThat(results.get("mine").get(0).get("score").asInt()).isEqualTo(1);

        perform(post("/api/health-checks/" + id + "/close"), bob).andExpect(status().isForbidden());
        perform(post("/api/health-checks/" + id + "/close"), alice).andExpect(status().isOk());
        perform(put("/api/health-checks/" + id + "/votes"), bob, j(Map.of("votes",
                List.of(Map.of("category", "Fun", "score", 3))))).andExpect(status().isConflict());
        assertThat(getJson("/api/health-checks/" + id, viewer).get("resultsVisible").asBoolean()).isTrue();
        JsonNode list = getJson("/api/projects/" + key + "/health-checks", viewer);
        assertThat(list.get(0).get("averages").get("Speed").asDouble()).isEqualTo(2.0);

        perform(delete("/api/health-checks/" + id), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/projects/" + key + "/health-checks", alice)).isEmpty();
    }
}
