package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Personal preferences (card fields, saved views…), project and epic icons, and the project image gallery. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.storage.dir=${java.io.tmpdir}/fakejira-test-ux-attachments")
class UxBackendTest extends ApiTestSupport {

    private String j(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    @Test
    void preferencesArePersonal() throws Exception {
        Account alice = register();
        Account bob = register();
        Map<String, Object> view = Map.of("name", "My bugs", "filters", Map.of("type", "BUG"), "grouping", "assignee");
        perform(put("/api/preferences/views:web"), alice, j(List.of(view))).andExpect(status().isOk());
        perform(put("/api/preferences/cards:web"), alice, j(Map.of("labels", false, "points", true))).andExpect(status().isOk());
        assertThat(getJson("/api/preferences/views:web", alice).get(0).get("name").asText()).isEqualTo("My bugs");
        assertThat(getJson("/api/preferences", alice).has("cards:web")).isTrue();
        assertThat(getJson("/api/preferences/views:web", bob).isNull()).isTrue();
        assertThat(getJson("/api/preferences", bob).size()).isZero();

        perform(put("/api/preferences/Bad Key"), alice, "1").andExpect(status().isBadRequest());
        perform(put("/api/preferences/huge"), alice, j("x".repeat(40_000))).andExpect(status().isBadRequest());
        perform(delete("/api/preferences/views:web"), alice).andExpect(status().isNoContent());
        assertThat(getJson("/api/preferences/views:web", alice).isNull()).isTrue();
    }

    @Test
    void projectsAndEpicsHaveIcons() throws Exception {
        Account owner = register();
        Account member = register();
        String key = project(owner, member);
        JsonNode project = read(perform(put("/api/projects/" + key + "/icon"), owner, body("icon", "🚀")).andExpect(status().isOk()));
        assertThat(project.get("icon").asText()).isEqualTo("🚀");
        perform(put("/api/projects/" + key + "/icon"), member, body("icon", "🐛")).andExpect(status().isForbidden());
        perform(put("/api/projects/" + key + "/icon"), owner, body("icon", "<script>")).andExpect(status().isBadRequest());
        perform(put("/api/projects/" + key + "/icon"), owner, body("icon", "abc")).andExpect(status().isBadRequest());

        long epic = read(perform(post("/api/projects/" + key + "/epics"), owner, body("name", "Payments"))).get("id").asLong();
        JsonNode updated = read(perform(put("/api/epics/" + epic + "/icon"), member, body("icon", "💳")).andExpect(status().isOk()));
        assertThat(updated.get("icon").asText()).isEqualTo("💳");
        long task = task(owner, key, "Card form", "epicId", epic).get("id").asLong();
        assertThat(getJson("/api/tasks/" + task, owner).get("epic").get("icon").asText()).isEqualTo("💳");
        perform(put("/api/epics/" + epic + "/icon"), member, body("icon", "")).andExpect(status().isOk());
        assertThat(getJson("/api/projects/" + key + "/epics", owner).get(0).get("icon").isNull()).isTrue();
        assertThat(getJson("/api/projects/" + key, member).get("icon").asText()).isEqualTo("🚀");
    }

    @Test
    void galleryShowsTheProjectsPictures() throws Exception {
        Account owner = register();
        Account outsider = register();
        String key = project(owner);
        long id = task(owner, key, "Mockups").get("id").asLong();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        perform(multipart("/api/tasks/" + id + "/attachments").file(new MockMultipartFile("file", "home.png", "image/png", png)), owner)
                .andExpect(status().isCreated());
        perform(multipart("/api/tasks/" + id + "/attachments").file(new MockMultipartFile("file", "notes.txt", "text/plain", "hi".getBytes())), owner)
                .andExpect(status().isCreated());
        JsonNode gallery = getJson("/api/projects/" + key + "/gallery", owner);
        assertThat(texts(gallery, "filename")).containsExactly("home.png");
        assertThat(gallery.get(0).get("task").get("id").asLong()).isEqualTo(id);
        perform(get("/api/projects/" + key + "/gallery"), outsider).andExpect(status().isNotFound());
    }
}
