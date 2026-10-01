package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
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

/** Presence, co-editing relay, edit conflicts, inline comments, guests, wiki, meetings, stand-up, polls, kudos. */
@SpringBootTest
@AutoConfigureMockMvc
class CollaborationTest extends ApiTestSupport {

    private String j(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    @Test
    void presenceCoEditingAndConflicts() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        JsonNode task = task(alice, key, "Shared doc", "description", "Hello");
        long id = task.get("id").asLong();

        perform(post("/api/tasks/" + id + "/presence"), alice, j(Map.of("clientId", "a1", "editing", false)))
                .andExpect(status().isOk());
        JsonNode present = read(perform(post("/api/tasks/" + id + "/presence"), bob, j(Map.of("clientId", "b1", "editing", true))));
        assertThat(present).hasSize(2);
        assertThat(present.toString()).contains("\"editing\":true");
        perform(post("/api/tasks/" + id + "/presence/leave"), alice, j(Map.of("clientId", "a1"))).andExpect(status().isOk());
        assertThat(getJson("/api/tasks/" + id + "/presence", bob)).hasSize(1);

        // Co-editing: the first editor seeds, later ones replay the log.
        JsonNode first = read(perform(post("/api/tasks/" + id + "/collab/join"), alice, j(Map.of("clientId", "a1"))));
        assertThat(first.get("seed").asBoolean()).isTrue();
        perform(post("/api/tasks/" + id + "/collab/update"), alice, j(Map.of("clientId", "a1", "update", "AQIDBA==")))
                .andExpect(status().isOk());
        JsonNode second = read(perform(post("/api/tasks/" + id + "/collab/join"), bob, j(Map.of("clientId", "b1"))));
        assertThat(second.get("seed").asBoolean()).isFalse();
        assertThat(second.get("updates").get(0).asText()).isEqualTo("AQIDBA==");
        perform(post("/api/tasks/" + id + "/collab/update"), bob, j(Map.of("clientId", "nope", "update", "AQ==")))
                .andExpect(status().isConflict());
        perform(post("/api/tasks/" + id + "/collab/update"), bob, j(Map.of("clientId", "b1", "update", "not base64!")))
                .andExpect(status().isBadRequest());

        // Saving over someone else's newer change is refused.
        Instant opened = Instant.parse(task.get("updatedAt").asText());
        Thread.sleep(5);
        perform(put("/api/tasks/" + id), bob, j(Map.of("title", "Shared doc v2", "priority", "HIGH", "labels", List.of())))
                .andExpect(status().isOk());
        perform(put("/api/tasks/" + id).param("expected", opened.toString()), alice,
                j(Map.of("title", "Mine", "priority", "LOW", "labels", List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(bob.username() + " changed this task while you were editing it."));
        JsonNode fresh = getJson("/api/tasks/" + id, alice);
        perform(put("/api/tasks/" + id).param("expected", fresh.get("updatedAt").asText()), alice,
                j(Map.of("title", "Mine", "priority", "LOW", "labels", List.of()))).andExpect(status().isOk());

        // Inline comment on a passage of the description.
        JsonNode inline = read(perform(post("/api/tasks/" + id + "/comments"), bob,
                j(Map.of("body", "Is this right?", "anchor", "  Hello   world  "))));
        assertThat(inline.get("anchor").asText()).isEqualTo("Hello world");
    }

    @Test
    void guestsCommentButSeeLess() throws Exception {
        Account alice = register();
        Account client = register();
        String key = project(alice);
        perform(post("/api/projects/" + key + "/members"), alice, j(Map.of("login", client.username(), "role", "GUEST")))
                .andExpect(status().isOk());
        long id = task(alice, key, "Landing page").get("id").asLong();
        perform(post("/api/tasks/" + id + "/time"), alice, j(Map.of("minutes", 30))).andExpect(status().isCreated());

        JsonNode asGuest = getJson("/api/projects/" + key, client);
        for (JsonNode m : asGuest.get("members")) {
            if (m.get("id").asLong() == client.id()) {
                assertThat(m.get("role").asText()).isEqualTo("GUEST");
                assertThat(m.get("email").asText()).isNotEmpty();
            } else {
                assertThat(m.get("email").isNull()).isTrue();
            }
        }
        JsonNode t = getJson("/api/tasks/" + id, client);
        assertThat(t.get("reporter").get("email").isNull()).isTrue();
        assertThat(getJson("/api/tasks/" + id, alice).get("reporter").get("email").asText()).contains("@example.com");
        perform(post("/api/tasks/" + id + "/comments"), client, body("body", "Looks great")).andExpect(status().isCreated());
        perform(patch("/api/tasks/" + id + "/status"), client, body("status", "DONE")).andExpect(status().isForbidden());
        assertThat(getJson("/api/tasks/" + id + "/activity", client)).isEmpty();
        assertThat(getJson("/api/tasks/" + id + "/time", client)).isEmpty();
        assertThat(getJson("/api/tasks/" + id + "/activity", alice)).isNotEmpty();

        // Invite links can carry the guest role.
        JsonNode invite = read(perform(post("/api/invites"), alice, j(Map.of("projectKey", key, "role", "GUEST"))));
        assertThat(invite.get("role").asText()).isEqualTo("GUEST");
        String name = uniqueName("guest");
        perform(post("/api/auth/register"), alice, j(Map.of("username", name, "email", name + "@example.com",
                "password", PASSWORD, "inviteCode", invite.get("code").asText()))).andExpect(status().isCreated());
        JsonNode members = getJson("/api/projects/" + key, alice).get("members");
        assertThat(members.toString()).contains("\"username\":\"" + name + "\"");
        boolean guest = false;
        for (JsonNode m : members) {
            if (m.get("username").asText().equals(name)) {
                guest = m.get("role").asText().equals("GUEST");
            }
        }
        assertThat(guest).isTrue();
        // Promoting a guest to member lifts the limits.
        perform(put("/api/projects/" + key + "/members/" + client.id() + "/role"), alice, j(Map.of("role", "MEMBER")))
                .andExpect(status().isOk());
        assertThat(getJson("/api/tasks/" + id + "/activity", client)).isNotEmpty();
    }

    @Test
    void wikiPagesWithHistory() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long taskId = task(alice, key, "Onboarding").get("id").asLong();
        JsonNode home = read(perform(post("/api/projects/" + key + "/wiki"), alice,
                j(Map.of("title", "Team handbook", "body", "# Welcome\nSee " + key + "-1 for onboarding."))));
        assertThat(home.get("slug").asText()).isEqualTo("team-handbook");
        JsonNode child = read(perform(post("/api/projects/" + key + "/wiki"), bob,
                j(Map.of("title", "Team handbook", "body", "Duplicate title", "parentId", home.get("id").asLong()))));
        assertThat(child.get("slug").asText()).isEqualTo("team-handbook-2");
        assertThat(child.get("path").get(0).get("title").asText()).isEqualTo("Team handbook");
        long pageId = home.get("id").asLong();
        perform(put("/api/wiki/" + pageId), bob, j(Map.of("title", "Team handbook", "body", "Edited by bob", "baseVersion", 1)))
                .andExpect(jsonPath("$.version").value(2));
        perform(put("/api/wiki/" + pageId), alice, j(Map.of("title", "Handbook", "body", "Stale edit", "baseVersion", 1)))
                .andExpect(status().isConflict());
        assertThat(getJson("/api/wiki/" + pageId + "/history", alice)).hasSize(2);
        assertThat(getJson("/api/wiki/" + pageId + "/history/1", alice).get("body").asText()).contains("Welcome");
        perform(post("/api/wiki/" + pageId + "/history/1/restore"), alice).andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("Welcome")));
        assertThat(getJson("/api/projects/" + key + "/wiki/team-handbook", bob).get("children")).hasSize(1);
        assertThat(texts(getJson("/api/tasks/" + taskId + "/wiki", alice), "title")).containsExactly("Team handbook");
        JsonNode hits = read(perform(get("/api/search/text").param("q", "welcome"), alice));
        assertThat(hits.toString()).contains("\"kind\":\"WIKI\"");
        // Moving a page under its own child is refused; deleting moves children up.
        perform(put("/api/wiki/" + pageId), alice, j(Map.of("title", "Team handbook", "body", "x", "parentId", child.get("id").asLong())))
                .andExpect(status().isBadRequest());
        perform(delete("/api/wiki/" + pageId), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/projects/" + key + "/wiki", alice).get(0).get("parentId").isNull()).isTrue();
    }

    @Test
    void meetingsStandupPollsAndKudos() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        JsonNode note = read(perform(post("/api/projects/" + key + "/meetings"), alice, j(Map.of("kind", "PLANNING",
                "title", "Sprint planning", "sprintId", sprint,
                "body", "Agreed:\n- [ ] Update the docs @" + bob.username() + "\n- [x] Done already\n- [ ] Book a room"))));
        long noteId = note.get("id").asLong();
        JsonNode withActions = read(perform(post("/api/meetings/" + noteId + "/actions/from-notes"), alice));
        assertThat(withActions.get("actions")).hasSize(2);
        assertThat(withActions.get("actions").get(0).get("assignee").get("username").asText()).isEqualTo(bob.username());
        read(perform(post("/api/meetings/" + noteId + "/actions/from-notes"), alice));
        JsonNode created = read(perform(post("/api/meetings/" + noteId + "/tasks"), alice));
        JsonNode docsTask = getJson("/api/tasks/" + created.get("actions").get(0).get("task").get("id").asLong(), alice);
        assertThat(docsTask.get("assignee").get("username").asText()).isEqualTo(bob.username());
        assertThat(docsTask.get("sprint").get("id").asLong()).isEqualTo(sprint);
        assertThat(getJson("/api/projects/" + key + "/meetings?sprint=" + sprint, bob)).hasSize(1);

        // Stand-up: bob finished the docs today; asking for tomorrow shows it as "since the last working day".
        long docsId = docsTask.get("id").asLong();
        perform(patch("/api/tasks/" + docsId + "/status"), bob, body("status", "IN_PROGRESS")).andExpect(status().isOk());
        JsonNode other = task(alice, key, "Blocked work", "assigneeId", bob.id());
        perform(post("/api/tasks/" + docsId + "/links"), alice, body("type", "BLOCKS", "targetKey", other.get("key").asText()))
                .andExpect(status().isCreated());
        JsonNode standup = getJson("/api/projects/" + key + "/standup?date=" + LocalDate.now().plusDays(1), alice);
        JsonNode bobRow = null;
        for (JsonNode p : standup.get("people")) {
            if (p.get("user").get("username").asText().equals(bob.username())) {
                bobRow = p;
            }
        }
        assertThat(bobRow).isNotNull();
        assertThat(texts(bobRow.get("today"), "title")).contains("Update the docs");
        assertThat(texts(bobRow.get("blocked"), "title")).contains("Blocked work");
        assertThat(texts(bobRow.get("workedOn"), "title")).contains("Update the docs");

        // Polls: vote, change your vote, close into the decision log.
        long poll = read(perform(post("/api/tasks/" + docsId + "/polls"), alice,
                j(Map.of("question", "Which format?", "options", List.of("Markdown", "PDF", "Video"))))).get("id").asLong();
        perform(post("/api/polls/" + poll + "/vote"), bob, j(Map.of("options", List.of(0, 1)))).andExpect(status().isBadRequest());
        perform(post("/api/polls/" + poll + "/vote"), bob, j(Map.of("options", List.of(1)))).andExpect(status().isOk());
        perform(post("/api/polls/" + poll + "/vote"), bob, j(Map.of("options", List.of(0)))).andExpect(status().isOk());
        JsonNode voted = read(perform(post("/api/polls/" + poll + "/vote"), alice, j(Map.of("options", List.of(0)))));
        assertThat(voted.get("options").get(0).get("votes").asInt()).isEqualTo(2);
        assertThat(voted.get("options").get(1).get("votes").asInt()).isZero();
        perform(post("/api/polls/" + poll + "/close"), bob, j(Map.of("recordDecision", true))).andExpect(status().isForbidden());
        perform(post("/api/polls/" + poll + "/close"), alice, j(Map.of("recordDecision", true)))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
        perform(post("/api/polls/" + poll + "/vote"), bob, j(Map.of("options", List.of(2)))).andExpect(status().isBadRequest());
        JsonNode log = getJson("/api/projects/" + key + "/decisions", bob);
        assertThat(log.get(0).get("text").asText()).isEqualTo("Which format? → Markdown");
        perform(post("/api/projects/" + key + "/decisions"), bob, j(Map.of("text", "Ship on Fridays", "context", "Fewer conflicts")))
                .andExpect(status().isCreated());
        assertThat(getJson("/api/projects/" + key + "/decisions", alice)).hasSize(2);

        // Kudos only on finished tasks, never to yourself.
        perform(post("/api/tasks/" + docsId + "/kudos"), alice, j(Map.of("toUserId", bob.id()))).andExpect(status().isBadRequest());
        perform(patch("/api/tasks/" + docsId + "/status"), bob, body("status", "DONE")).andExpect(status().isOk());
        perform(post("/api/tasks/" + docsId + "/kudos"), bob, j(Map.of("toUserId", bob.id()))).andExpect(status().isBadRequest());
        perform(post("/api/tasks/" + docsId + "/kudos"), alice, j(Map.of("toUserId", bob.id(), "message", "Great docs", "emoji", "🚀")))
                .andExpect(jsonPath("$.emoji").value("🚀"));
        JsonNode wall = getJson("/api/kudos", bob);
        assertThat(wall.get("recent").get(0).get("message").asText()).isEqualTo("Great docs");
        assertThat(wall.get("thisMonth").get(0).get("user").get("username").asText()).isEqualTo(bob.username());
        assertThat(getJson("/api/notifications", bob).toString()).contains("thanked you");
    }
}
