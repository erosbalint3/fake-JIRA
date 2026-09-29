package com.fakejira;

import com.fakejira.search.Fql;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Query language, text search, stats, teams and group mentions, dashboards, recent, calendar, feed, away. */
@SpringBootTest
@AutoConfigureMockMvc
class SearchAndTeamTest extends ApiTestSupport {

    private JsonNode getQ(String path, String q, Account account) throws Exception {
        return read(perform(get(path).param("q", q), account).andExpect(status().isOk()));
    }

    private List<String> search(Account account, String q) throws Exception {
        return texts(getQ("/api/search", q, account).get("tasks"), "title");
    }

    @Test
    void parserReportsErrorsWithPositions() {
        assertThat(Fql.parse("project = WEB and (status = done OR NOT priority > low) ORDER BY due desc, key").order())
                .hasSize(2);
        assertThatThrownBy(() -> Fql.parse("status = done AND")).hasMessageContaining("ends too early");
        assertThatThrownBy(() -> Fql.parse("status done")).isInstanceOf(Fql.FqlException.class)
                .satisfies(e -> assertThat(((Fql.FqlException) e).position()).isEqualTo(7));
        assertThatThrownBy(() -> Fql.parse("title ~ \"open")).hasMessageContaining("quote");
        assertThatThrownBy(() -> Fql.parse("(status = done")).hasMessageContaining("parenthesis");
    }

    @Test
    void queryLanguageFiltersAndSorts() throws Exception {
        Account alice = register();
        Account bob = register();
        Account outsider = register();
        String key = project(alice, bob);
        String other = project(outsider);
        task(outsider, other, "Secret elsewhere", "priority", "CRITICAL");
        long crash = task(alice, key, "Checkout crash", "type", "BUG", "priority", "CRITICAL", "assigneeId", bob.id(),
                "labels", List.of("payments"), "dueDate", LocalDate.now().plusDays(2).toString(), "storyPoints", 5).get("id").asLong();
        task(alice, key, "Landing copy", "type", "STORY", "priority", "LOW", "storyPoints", 2);
        long slow = task(alice, key, "Slow search", "priority", "HIGH", "assigneeId", alice.id(),
                "description", "The query planner picks a table scan").get("id").asLong();
        perform(post("/api/tasks/" + slow + "/comments"), bob, body("body", "Maybe an index on tasks.updated_at?"));
        perform(patch("/api/tasks/" + crash + "/status"), alice, body("status", "IN_PROGRESS"));

        assertThat(search(alice, "project = " + key + " AND priority >= high ORDER BY priority DESC"))
                .containsExactly("Checkout crash", "Slow search");
        assertThat(search(outsider, "priority = critical")).containsExactly("Secret elsewhere");
        assertThat(search(bob, "assignee = me")).containsExactly("Checkout crash");
        assertThat(search(alice, "assignee is empty")).containsExactly("Landing copy");
        assertThat(search(alice, "assignee != " + bob.username() + " ORDER BY title")).containsExactly("Landing copy", "Slow search");
        assertThat(search(alice, "status = \"in progress\"")).containsExactly("Checkout crash");
        assertThat(search(alice, "status in (todo, review) ORDER BY title")).containsExactly("Landing copy", "Slow search");
        assertThat(search(alice, "type = bug OR type = story ORDER BY key")).containsExactly("Checkout crash", "Landing copy");
        assertThat(search(alice, "label = payments")).containsExactly("Checkout crash");
        assertThat(search(alice, "label is empty ORDER BY title")).containsExactly("Landing copy", "Slow search");
        assertThat(search(alice, "due <= +7d")).containsExactly("Checkout crash");
        assertThat(search(alice, "due is empty AND created >= today ORDER BY title")).containsExactly("Landing copy", "Slow search");
        assertThat(search(alice, "points > 2")).containsExactly("Checkout crash");
        assertThat(search(alice, "text ~ \"index updated\"")).containsExactly("Slow search");
        assertThat(search(alice, "description ~ planner")).containsExactly("Slow search");
        assertThat(search(alice, "NOT (priority = low) AND project = " + key + " ORDER BY key")).containsExactly("Checkout crash", "Slow search");
        assertThat(search(alice, "key = " + key + "-1")).containsExactly("Checkout crash");
        assertThat(search(alice, "watcher = " + bob.username())).contains("Slow search");

        perform(get("/api/search").param("q", "colour = red"), alice).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Unknown field 'colour'")))
                .andExpect(jsonPath("$.fieldErrors.position").value("0"));
        perform(get("/api/search").param("q", "priority = urgent"), alice).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.position").value("11"));
        perform(get("/api/search").param("q", "title = x"), alice).andExpect(status().isBadRequest());

        JsonNode stats = read(perform(get("/api/search/stats").param("groupBy", "priority").param("q", "project = " + key), alice));
        assertThat(texts(stats, "key")).containsExactly("CRITICAL", "HIGH", "LOW");
        assertThat(stats.get(0).get("points").asInt()).isEqualTo(5);
        assertThat(getJson("/api/search/fields", alice).size()).isGreaterThan(15);
    }

    @Test
    void textSearchFindsTasksCommentsAndEpics() throws Exception {
        Account alice = register();
        String key = project(alice);
        long id = task(alice, key, "Refund flow", "description", "Customers want refunds via the portal").get("id").asLong();
        task(alice, key, "Portal login").get("id").asLong();
        perform(post("/api/tasks/" + id + "/comments"), alice, body("body", "Stripe supports partial refunds too"));
        perform(post("/api/projects/" + key + "/epics"), alice, body("name", "Refund portal"));

        JsonNode hits = getQ("/api/search/text", "refund", alice);
        assertThat(texts(hits, "kind")).contains("TASK", "COMMENT", "EPIC");
        assertThat(hits.get(0).get("kind").asText()).isEqualTo("TASK");
        JsonNode both = getQ("/api/search/text", "partial stripe", alice);
        assertThat(both).hasSize(1);
        assertThat(both.get(0).get("snippet").asText()).contains("partial refunds");
        assertThat(getQ("/api/search/text", key + "-1", alice).get(0).get("taskId").asLong()).isEqualTo(id);
    }

    @Test
    void teamsPowerMentionsAndMembersOf() throws Exception {
        Account alice = register();
        Account bob = register();
        Account carol = register();
        String key = project(alice, bob);
        String handle = "design" + System.nanoTime() % 100000;
        JsonNode team = read(perform(post("/api/teams"), bob, body("name", "Design", "handle", handle))
                .andExpect(status().isCreated()));
        long teamId = team.get("id").asLong();
        perform(post("/api/teams/" + teamId + "/members"), bob, body("login", carol.username())).andExpect(status().isOk());
        perform(post("/api/teams/" + teamId + "/members"), alice, body("login", alice.username())).andExpect(status().isForbidden());
        perform(post("/api/teams"), alice, body("name", "Dup", "handle", handle.toUpperCase())).andExpect(status().isBadRequest());
        perform(post("/api/teams"), alice, body("name", "Clash", "handle", bob.username())).andExpect(status().isBadRequest());

        // @design notifies team members who are in the project (bob), not carol (not a member).
        long id = task(alice, key, "Pick colours").get("id").asLong();
        perform(post("/api/tasks/" + id + "/comments"), alice, body("body", "@" + handle + " can you have a look?"));
        assertThat(getJson("/api/notifications", bob).get("items").toString()).contains("mentioned you");
        assertThat(getJson("/api/notifications", carol).get("items")).isEmpty();

        perform(put("/api/tasks/" + id + "/assignee"), alice, "{\"assigneeId\":" + bob.id() + "}");
        assertThat(search(alice, "assignee in membersOf(" + handle + ")")).containsExactly("Pick colours");
        perform(get("/api/search").param("q", "assignee in membersOf(nope)"), alice).andExpect(status().isBadRequest());

        perform(delete("/api/teams/" + teamId + "/members/" + carol.id()), carol).andExpect(status().isOk());
        perform(delete("/api/teams/" + teamId), bob).andExpect(status().isNoContent());
    }

    @Test
    void dashboardsAndRecentlyViewed() throws Exception {
        Account alice = register();
        String key = project(alice);
        JsonNode list = getJson("/api/dashboards", alice);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("widgets").size()).isEqualTo(5);
        long id = list.get(0).get("id").asLong();
        String widgets = "[{\"type\":\"counter\",\"title\":\"Open bugs\",\"query\":\"type = bug\"}]";
        perform(put("/api/dashboards/" + id), alice, "{\"name\":\"Mine\",\"widgets\":" + widgets + "}")
                .andExpect(jsonPath("$.widgets[0].type").value("counter"));
        perform(put("/api/dashboards/" + id), alice, "{\"name\":\"Mine\",\"widgets\":[{\"type\":\"iframe\"}]}")
                .andExpect(status().isBadRequest());
        perform(put("/api/dashboards/" + id), register(), "{\"name\":\"x\",\"widgets\":[]}").andExpect(status().isNotFound());

        long a = task(alice, key, "First").get("id").asLong();
        long b = task(alice, key, "Second").get("id").asLong();
        perform(post("/api/recent/" + a), alice).andExpect(status().isNoContent());
        perform(post("/api/recent/" + b), alice);
        perform(post("/api/recent/" + a), alice);
        assertThat(texts(getJson("/api/recent", alice), "title")).containsExactly("First", "Second");
        perform(delete("/api/tasks/" + a), alice);
        assertThat(texts(getJson("/api/recent", alice), "title")).containsExactly("Second");
    }

    @Test
    void calendarFeedAndOutOfOffice() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        LocalDate due = LocalDate.now().plusDays(3);
        task(alice, key, "Ship newsletter, v2; final", "dueDate", due.toString(), "assigneeId", alice.id());
        perform(post("/api/projects/" + key + "/releases"), alice, "{\"name\":\"2.0\",\"releaseDate\":\"" + due.plusDays(1) + "\"}");
        perform(put("/api/profile/away"), bob, "{\"from\":\"" + LocalDate.now() + "\",\"until\":\"" + due + "\",\"message\":\"Holiday\"}")
                .andExpect(jsonPath("$.awayUntil").value(due.toString()));
        perform(put("/api/profile/away"), bob, "{\"until\":\"2001-01-01\"}").andExpect(status().isBadRequest());

        JsonNode events = getJson("/api/calendar?from=" + LocalDate.now() + "&to=" + due.plusDays(7), alice);
        assertThat(texts(events, "kind")).contains("TASK", "RELEASE", "AWAY");
        JsonNode members = getJson("/api/projects/" + key, alice).get("members");
        boolean bobAway = false;
        for (JsonNode m : members) {
            if (m.get("id").asLong() == bob.id()) {
                bobAway = m.get("awayUntil").asText().equals(due.toString());
            }
        }
        assertThat(bobAway).isTrue();

        assertThat(getJson("/api/profile/calendar-feed", alice).get("url").isNull()).isTrue();
        assertThat(getJson("/api/profile", alice).get("calendarFeed").asBoolean()).isFalse();
        String url = read(perform(post("/api/profile/calendar-feed"), alice)).get("url").asText();
        String path = url.substring(url.indexOf("/api/"));
        String ics = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n").contains("DTSTART;VALUE=DATE:" + due.toString().replace("-", ""))
                .contains("Ship newsletter\\, v2\\; final").contains("END:VCALENDAR");
        // A new URL revokes the old one.
        perform(post("/api/profile/calendar-feed"), alice);
        mvc.perform(get(path)).andExpect(status().isNotFound());
        perform(delete("/api/profile/away"), bob).andExpect(jsonPath("$.awayUntil").doesNotExist());
    }

    @Test
    void activityFeedMergesChangesAndComments() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long id = task(alice, key, "Feed me").get("id").asLong();
        perform(patch("/api/tasks/" + id + "/status"), bob, body("status", "IN_PROGRESS"));
        perform(post("/api/tasks/" + id + "/comments"), bob, body("body", "Started on it"));
        JsonNode feed = getJson("/api/activity?project=" + key, alice);
        assertThat(feed.get(0).get("kind").asText()).isEqualTo("comment");
        assertThat(feed.get(0).get("body").asText()).isEqualTo("Started on it");
        assertThat(texts(feed, "kind")).contains("change");
        assertThat(getJson("/api/activity?project=" + key + "&user=" + alice.username(), alice).toString()).doesNotContain("Started on it");
        String before = feed.get(0).get("createdAt").asText();
        assertThat(getJson("/api/activity?project=" + key + "&before=" + before, alice).toString()).doesNotContain("Started on it");
        assertThat(getJson("/api/activity", register())).isEmpty();
    }
}
