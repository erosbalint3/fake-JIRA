package com.fakejira;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.storage.dir=${java.io.tmpdir}/fakejira-test-attachments")
class AttachmentApiTest extends ApiTestSupport {

    @Test
    void uploadDownloadAndDeleteAttachments() throws Exception {
        Account alice = register();
        Account bob = register();
        Account outsider = register();
        String key = project(alice, bob);
        long id = task(alice, key, "With files").get("id").asLong();

        MockMultipartFile file = new MockMultipartFile("file", "../../evil\"name.html", "text/html",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        long attachmentId = read(perform(multipart("/api/tasks/" + id + "/attachments").file(file), bob)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("evilname.html"))
                .andExpect(jsonPath("$.size").value(25))).get("id").asLong();

        perform(multipart("/api/tasks/" + id + "/attachments").file(file), outsider).andExpect(status().isNotFound());
        perform(get("/api/tasks/" + id + "/attachments"), alice).andExpect(jsonPath("$[0].id").value(attachmentId));

        // Always a download, never rendered inline.
        perform(get("/api/attachments/" + attachmentId + "/content"), alice)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string("<script>alert(1)</script>"));
        perform(get("/api/attachments/" + attachmentId + "/content"), outsider).andExpect(status().isNotFound());

        assertThat(texts(getJson("/api/tasks/" + id + "/activity", alice), "message")).contains("attached evilname.html");

        // Uploader or project owner may delete; alice owns the project.
        Account carol = register();
        perform(delete("/api/attachments/" + attachmentId), carol).andExpect(status().isNotFound());
        perform(delete("/api/attachments/" + attachmentId), alice).andExpect(status().isNoContent());
        perform(get("/api/attachments/" + attachmentId + "/content"), alice).andExpect(status().isNotFound());
    }

    @Test
    void rejectsEmptyAndMissingFiles() throws Exception {
        Account alice = register();
        String key = project(alice);
        long id = task(alice, key, "x").get("id").asLong();
        perform(multipart("/api/tasks/" + id + "/attachments")
                .file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])), alice)
                .andExpect(status().isBadRequest());
        perform(multipart("/api/tasks/" + id + "/attachments"), alice).andExpect(status().isBadRequest());
    }
}
