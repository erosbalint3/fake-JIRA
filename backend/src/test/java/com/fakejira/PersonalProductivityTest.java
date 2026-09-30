package com.fakejira;

import com.fakejira.notification.NotificationController;
import com.fakejira.personal.ReminderController;
import com.fakejira.personal.RunningTimer;
import com.fakejira.personal.RunningTimerRepository;
import com.fakejira.personal.TimerController;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Notification rules, quiet hours, inbox triage, reminders, the timer, the Today list and personal notes. */
@SpringBootTest
@AutoConfigureMockMvc
class PersonalProductivityTest extends ApiTestSupport {

    @Autowired
    NotificationController notificationController;
    @Autowired
    ReminderController reminderController;
    @Autowired
    TimerController timerController;
    @Autowired
    RunningTimerRepository timers;
    @Autowired
    JdbcTemplate jdbc;

    private String j(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private List<String> inbox(Account account) throws Exception {
        return texts(getJson("/api/notifications", account).get("items"), "message");
    }

    @Test
    void notificationRulesMuteAndDirectOnly() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long id = task(bob, key, "Watched by bob").get("id").asLong();

        // Direct only: a plain comment is dropped, a mention gets through.
        perform(put("/api/notifications/settings/projects/" + key), bob, j(Map.of("level", "DIRECT")))
                .andExpect(status().isOk());
        perform(post("/api/tasks/" + id + "/comments"), alice, body("body", "Just a comment")).andExpect(status().isCreated());
        assertThat(inbox(bob)).noneMatch(m -> m.contains("commented on"));
        perform(post("/api/tasks/" + id + "/comments"), alice, body("body", "Hey @" + bob.username()))
                .andExpect(status().isCreated());
        assertThat(inbox(bob)).anyMatch(m -> m.contains("mentioned you in"));

        // Muted: nothing at all, not even direct ones.
        perform(put("/api/notifications/settings/projects/" + key), bob, j(Map.of("level", "MUTED")))
                .andExpect(status().isOk());
        int before = inbox(bob).size();
        perform(post("/api/tasks/" + id + "/comments"), alice, body("body", "Again @" + bob.username()))
                .andExpect(status().isCreated());
        assertThat(inbox(bob)).hasSize(before);

        JsonNode settings = getJson("/api/notifications/settings", bob);
        assertThat(settings.get("projects").get(0).get("level").asText()).isEqualTo("MUTED");
        assertThat(getJson("/api/notifications/settings/projects/" + key, bob).get("level").asText()).isEqualTo("MUTED");

        // Removing the project rule falls back to the default (everything).
        perform(delete("/api/notifications/settings/projects/" + key), bob).andExpect(status().isOk());
        perform(post("/api/tasks/" + id + "/comments"), alice, body("body", "Back")).andExpect(status().isCreated());
        assertThat(inbox(bob)).anyMatch(m -> m.contains("commented on"));

        // A default of "direct only" applies to every project.
        perform(put("/api/notifications/settings/defaults"), bob, j(Map.of("level", "DIRECT"))).andExpect(status().isOk());
        before = inbox(bob).size();
        perform(post("/api/tasks/" + id + "/comments"), alice, body("body", "Ignored")).andExpect(status().isCreated());
        assertThat(inbox(bob)).hasSize(before);

        // Leaving the project drops its rules; outsiders cannot set one.
        Account carol = register();
        perform(put("/api/notifications/settings/projects/" + key), carol, j(Map.of("level", "MUTED")))
                .andExpect(status().isNotFound());
    }

    @Test
    void quietHoursValidation() throws Exception {
        Account alice = register();
        Map<String, Object> request = new HashMap<>();
        request.put("timeZone", "Europe/Budapest");
        request.put("from", "22:00");
        request.put("to", "07:00");
        JsonNode saved = read(perform(put("/api/notifications/settings/quiet-hours"), alice, j(request))
                .andExpect(status().isOk()));
        assertThat(saved.get("quietHours").get("timeZone").asText()).isEqualTo("Europe/Budapest");
        assertThat(saved.get("quietHours").get("from").asText()).startsWith("22:00");

        request.put("timeZone", "Mars/Olympus");
        perform(put("/api/notifications/settings/quiet-hours"), alice, j(request)).andExpect(status().isBadRequest());
        request.put("timeZone", "UTC");
        request.put("to", null);
        perform(put("/api/notifications/settings/quiet-hours"), alice, j(request)).andExpect(status().isBadRequest());
        request.put("from", null);
        perform(put("/api/notifications/settings/quiet-hours"), alice, j(request)).andExpect(status().isOk());
    }

    @Test
    void quietHoursWrapPastMidnight() {
        com.fakejira.user.User user = new com.fakejira.user.User("q", "q@example.com", "x");
        user.setTimeZone("UTC");
        user.setQuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0));
        assertThat(user.isQuietAt(Instant.parse("2026-01-01T23:30:00Z"))).isTrue();
        assertThat(user.isQuietAt(Instant.parse("2026-01-01T06:59:00Z"))).isTrue();
        assertThat(user.isQuietAt(Instant.parse("2026-01-01T07:00:00Z"))).isFalse();
        assertThat(user.isQuietAt(Instant.parse("2026-01-01T12:00:00Z"))).isFalse();
        user.setQuietHours(LocalTime.of(12, 0), LocalTime.of(13, 0));
        assertThat(user.isQuietAt(Instant.parse("2026-01-01T12:30:00Z"))).isTrue();
        assertThat(user.isQuietAt(Instant.parse("2026-01-01T23:30:00Z"))).isFalse();
    }

    @Test
    void inboxTriageSnoozeAndDone() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long id = task(alice, key, "Triage me", "assigneeId", bob.id()).get("id").asLong();
        perform(post("/api/tasks/" + id + "/comments"), alice, body("body", "One")).andExpect(status().isCreated());
        JsonNode items = getJson("/api/notifications", bob).get("items");
        int total = items.size();
        assertThat(total).isGreaterThanOrEqualTo(2);
        long first = items.get(0).get("id").asLong();
        long second = items.get(1).get("id").asLong();

        perform(post("/api/notifications/triage"), bob, j(Map.of("ids", List.of(first), "action", "done")))
                .andExpect(status().isNoContent());
        assertThat(ids(getJson("/api/notifications", bob).get("items"))).doesNotContain(first).contains(second);
        assertThat(ids(getJson("/api/notifications?view=done", bob).get("items"))).containsExactly(first);

        // Snoozing needs a future time; it hides the notification until then.
        perform(post("/api/notifications/triage"), bob, j(Map.of("ids", List.of(second), "action", "snooze",
                "until", Instant.now().minusSeconds(5).toString()))).andExpect(status().isBadRequest());
        perform(post("/api/notifications/triage"), bob, j(Map.of("ids", List.of(second), "action", "snooze",
                "until", Instant.now().plus(1, ChronoUnit.HOURS).toString()))).andExpect(status().isNoContent());
        assertThat(ids(getJson("/api/notifications", bob).get("items"))).doesNotContain(first, second);
        assertThat(getJson("/api/notifications", bob).get("unread").asLong()).isEqualTo(total - 2);
        assertThat(ids(getJson("/api/notifications?view=snoozed", bob).get("items"))).containsExactly(second);

        // When the time comes it is back on top, unread.
        jdbc.update("update notifications set snoozed_until = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), second);
        notificationController.wakeSnoozed();
        JsonNode woken = getJson("/api/notifications", bob);
        assertThat(woken.get("items").get(0).get("id").asLong()).isEqualTo(second);
        assertThat(woken.get("unread").asLong()).isEqualTo(total - 1);

        // Undone brings a done one back; alice cannot touch bob's notifications.
        perform(post("/api/notifications/triage"), bob, j(Map.of("ids", List.of(first), "action", "undone")))
                .andExpect(status().isNoContent());
        assertThat(getJson("/api/notifications", bob).get("items")).hasSize(total);
        perform(post("/api/notifications/triage"), alice, j(Map.of("ids", List.of(first), "action", "done")))
                .andExpect(status().isNoContent());
        assertThat(getJson("/api/notifications", bob).get("items")).hasSize(total);

        perform(post("/api/notifications/read-all"), bob).andExpect(status().isNoContent());
        assertThat(read(perform(post("/api/notifications/done-read"), bob)).get("done").asInt()).isEqualTo(total);
        assertThat(getJson("/api/notifications", bob).get("items")).isEmpty();
    }

    @Test
    void remindersArriveAsNotifications() throws Exception {
        Account alice = register();
        String key = project(alice);
        long id = task(alice, key, "Remind me").get("id").asLong();

        perform(post("/api/reminders"), alice, j(Map.of("taskId", id,
                "remindAt", Instant.now().minus(1, ChronoUnit.HOURS).toString()))).andExpect(status().isBadRequest());
        perform(post("/api/reminders"), alice, j(Map.of("remindAt", Instant.now().plus(1, ChronoUnit.HOURS).toString())))
                .andExpect(status().isBadRequest());
        JsonNode reminder = read(perform(post("/api/reminders"), alice, j(Map.of("taskId", id, "note", "Check the build",
                "remindAt", Instant.now().plus(1, ChronoUnit.HOURS).toString()))).andExpect(status().isCreated()));
        perform(post("/api/reminders"), alice, j(Map.of("note", "Stand-up",
                "remindAt", Instant.now().plus(2, ChronoUnit.HOURS).toString()))).andExpect(status().isCreated());
        assertThat(getJson("/api/reminders", alice)).hasSize(2);
        assertThat(getJson("/api/tasks/" + id + "/reminders", alice)).hasSize(1);

        jdbc.update("update reminders set remind_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), reminder.get("id").asLong());
        reminderController.sendDue();
        assertThat(inbox(alice)).anyMatch(m -> m.startsWith("⏰ Reminder: " + key + "-") && m.endsWith("Check the build"));
        assertThat(getJson("/api/reminders", alice)).hasSize(1);

        // Reminders are private.
        Account bob = register();
        long other = getJson("/api/reminders", alice).get(0).get("id").asLong();
        perform(delete("/api/reminders/" + other), bob).andExpect(status().isNotFound());
        perform(delete("/api/reminders/" + other), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/reminders", alice)).isEmpty();
    }

    @Test
    void timerLogsTime() throws Exception {
        Account alice = register();
        Account viewer = register();
        String key = project(alice, viewer);
        perform(put("/api/projects/" + key + "/members/" + viewer.id() + "/role"), alice, body("role", "VIEWER"))
                .andExpect(status().isOk());
        long a = task(alice, key, "First").get("id").asLong();
        long b = task(alice, key, "Second").get("id").asLong();

        perform(get("/api/timer"), alice).andExpect(status().isNoContent());
        perform(post("/api/timer/start"), viewer, j(Map.of("taskId", a))).andExpect(status().isForbidden());
        JsonNode started = read(perform(post("/api/timer/start"), alice, j(Map.of("taskId", a))).andExpect(status().isOk()));
        assertThat(started.get("running").get("task").get("id").asLong()).isEqualTo(a);
        assertThat(getJson("/api/timer", alice).get("task").get("id").asLong()).isEqualTo(a);

        // Pretend it ran for 25 minutes, then switch tasks: the first one is logged.
        jdbc.update("update running_timers set started_at = ? where task_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(25, ChronoUnit.MINUTES)), a);
        JsonNode switched = read(perform(post("/api/timer/start"), alice, j(Map.of("taskId", b))).andExpect(status().isOk()));
        assertThat(switched.get("logged").get("minutes").asInt()).isBetween(25, 26);
        assertThat(getJson("/api/tasks/" + a + "/time", alice).get(0).get("note").asText()).isEqualTo("Timer");

        // Stop with an override (idle time removed).
        JsonNode stopped = read(perform(post("/api/timer/stop"), alice, j(Map.of("minutes", 10, "note", "Pairing")))
                .andExpect(status().isOk()));
        assertThat(stopped.get("logged").get("minutes").asInt()).isEqualTo(10);
        perform(post("/api/timer/stop"), alice, "{}").andExpect(status().isNotFound());

        // Discard logs nothing.
        perform(post("/api/timer/start"), alice, j(Map.of("taskId", a))).andExpect(status().isOk());
        perform(delete("/api/timer"), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/tasks/" + a + "/time", alice)).hasSize(1);

        // Long-running timers get one reminder.
        perform(post("/api/timer/start"), alice, j(Map.of("taskId", a))).andExpect(status().isOk());
        jdbc.update("update running_timers set started_at = ? where task_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(9, ChronoUnit.HOURS)), a);
        timerController.remindLongRunning();
        timerController.remindLongRunning();
        assertThat(inbox(alice).stream().filter(m -> m.startsWith("⏱ Your timer"))).hasSize(1);
        assertThat(timers.findByUserId(alice.id())).map(RunningTimer::getRemindedAt).isPresent();
    }

    @Test
    void todayListAndSummary() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        long due = task(alice, key, "Due today", "assigneeId", alice.id(), "dueDate", today.toString()).get("id").asLong();
        long later = task(alice, key, "Someday", "assigneeId", alice.id()).get("id").asLong();
        long third = task(alice, key, "Third", "assigneeId", alice.id()).get("id").asLong();

        JsonNode day = getJson("/api/today", alice);
        assertThat(ids(day.get("picks"))).isEmpty();
        assertThat(ids(day.get("suggestions"))).contains(due).doesNotContain(later);

        perform(post("/api/today"), alice, j(Map.of("taskId", later))).andExpect(status().isCreated());
        perform(post("/api/today"), alice, j(Map.of("taskId", due))).andExpect(status().isCreated());
        day = read(perform(post("/api/today"), alice, j(Map.of("taskId", due))).andExpect(status().isCreated()));
        assertThat(ids(day.get("picks"))).containsExactly(later, due);
        assertThat(ids(day.get("suggestions"))).doesNotContain(due);

        day = read(perform(put("/api/today/order"), alice, j(Map.of("taskIds", List.of(due, later)))).andExpect(status().isOk()));
        assertThat(ids(day.get("picks"))).containsExactly(due, later);

        // Picks are personal.
        assertThat(getJson("/api/today", bob).get("picks")).isEmpty();
        Account outsider = register();
        perform(post("/api/today"), outsider, j(Map.of("taskId", due))).andExpect(status().isNotFound());

        // Finish one, log time, and the summary shows it.
        perform(patch("/api/tasks/" + due + "/status"), alice, body("status", "DONE")).andExpect(status().isOk());
        perform(post("/api/tasks/" + later + "/time"), alice, j(Map.of("minutes", 90))).andExpect(status().isCreated());
        JsonNode summary = getJson("/api/today/summary", alice);
        assertThat(ids(summary.get("completed"))).containsExactly(due);
        assertThat(ids(summary.get("unfinished"))).containsExactly(later);
        assertThat(summary.get("minutesLogged").asLong()).isEqualTo(90);
        assertThat(summary.get("text").asText()).contains("Due today").contains("1h 30m");

        // Tomorrow offers the unfinished pick to carry over.
        String tomorrow = today.plusDays(1).toString();
        JsonNode next = getJson("/api/today?date=" + tomorrow, alice);
        assertThat(ids(next.get("carryOver"))).containsExactly(later);
        assertThat(next.get("previousDay").asText()).isEqualTo(today.toString());
        next = read(perform(post("/api/today/carry-over?date=" + tomorrow), alice).andExpect(status().isOk()));
        assertThat(ids(next.get("picks"))).containsExactly(later);
        assertThat(next.get("carryOver")).isEmpty();

        next = read(perform(delete("/api/today/" + later + "?date=" + tomorrow), alice).andExpect(status().isOk()));
        assertThat(next.get("picks")).isEmpty();

        // Deleting a task removes it from every list.
        perform(post("/api/today"), alice, j(Map.of("taskId", third))).andExpect(status().isCreated());
        perform(delete("/api/tasks/" + third), alice).andExpect(status().isNoContent());
        assertThat(ids(getJson("/api/today", alice).get("picks"))).doesNotContain(third);
    }

    @Test
    void personalNotesArePrivate() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long id = task(alice, key, "Private stuff").get("id").asLong();

        JsonNode mine = read(perform(put("/api/tasks/" + id + "/personal/note"), alice, body("body", "My *secret* plan"))
                .andExpect(status().isOk()));
        assertThat(mine.get("note").asText()).isEqualTo("My *secret* plan");
        mine = read(perform(post("/api/tasks/" + id + "/personal/items"), alice, body("text", "Ask Bob"))
                .andExpect(status().isCreated()));
        long item = mine.get("items").get(0).get("id").asLong();
        perform(post("/api/tasks/" + id + "/personal/items"), alice, body("text", " ")).andExpect(status().isBadRequest());

        JsonNode theirs = getJson("/api/tasks/" + id + "/personal", bob);
        assertThat(theirs.get("note").asText()).isEmpty();
        assertThat(theirs.get("items")).isEmpty();
        perform(patch("/api/personal-items/" + item), bob, j(Map.of("done", true))).andExpect(status().isNotFound());

        mine = read(perform(patch("/api/personal-items/" + item), alice, j(Map.of("done", true))).andExpect(status().isOk()));
        assertThat(mine.get("items").get(0).get("done").asBoolean()).isTrue();

        // Leaving the project drops the private data there.
        perform(put("/api/tasks/" + id + "/personal/note"), bob, body("body", "Bob's note")).andExpect(status().isOk());
        perform(delete("/api/projects/" + key + "/members/" + bob.id()), alice).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from personal_notes where user_id = ?", Integer.class, bob.id()))
                .isZero();

        mine = read(perform(delete("/api/personal-items/" + item), alice).andExpect(status().isOk()));
        assertThat(mine.get("items")).isEmpty();
        mine = read(perform(put("/api/tasks/" + id + "/personal/note"), alice, body("body", "")).andExpect(status().isOk()));
        assertThat(mine.get("note").asText()).isEmpty();
    }
}
