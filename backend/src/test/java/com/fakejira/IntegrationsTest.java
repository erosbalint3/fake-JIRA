package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.storage.dir=${java.io.tmpdir}/fakejira-test-int/attachments",
        "app.base-url=https://jira.example.test"})
class IntegrationsTest extends ApiTestSupport {

    @Test
    void csvExportEscapesAndImportCreatesTasks() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        task(alice, key, "=HYPERLINK(\"http://evil\")", "description", "line one\nline, two", "labels", java.util.List.of("ui"));

        String csv = perform(get("/api/projects/" + key + "/export.csv"), alice)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Key,Type,Title,Description");
        assertThat(csv).contains(key + "-1,Task,\"'=HYPERLINK(\"\"http://evil\"\")\",\"line one\nline, two\",To do,Medium");

        String upload = "Title,Description,Status,Priority,Assignee,Labels,Due date,Story points,Epic\r\n"
                + "Imported one,\"multi\nline\",In progress,High," + bob.username() + ",a;b,2026-12-01,5,Launch\r\n"
                + "Imported two,,done,low,,,,,Launch\r\n"
                + ",missing title,,,,,,,\r\n"
                + "Bad status,,Someday,,,,,,\r\n"
                + "Bad assignee,,,,nobody-here,,,,\r\n";
        JsonNode result = read(perform(multipart("/api/projects/" + key + "/import")
                .file(new MockMultipartFile("file", "tasks.csv", "text/csv", upload.getBytes(StandardCharsets.UTF_8))), alice)
                .andExpect(status().isOk()));
        assertThat(result.get("created").asInt()).isEqualTo(2);
        assertThat(result.get("errors")).hasSize(3);
        // Rows count records, so the quoted multi-line cell above does not shift them.
        assertThat(result.get("errors").get(0).get("row").asInt()).isEqualTo(4);

        JsonNode imported = getJson("/api/tasks/key/" + key + "-2", alice);
        assertThat(imported.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(imported.get("assignee").get("id").asLong()).isEqualTo(bob.id());
        assertThat(imported.get("labels").toString()).isEqualTo("[\"a\",\"b\"]");
        assertThat(imported.get("storyPoints").asInt()).isEqualTo(5);
        assertThat(imported.get("description").asText()).isEqualTo("multi\nline");
        assertThat(imported.get("epic").get("name").asText()).isEqualTo("Launch");
        assertThat(getJson("/api/projects/" + key + "/epics", alice)).hasSize(1);

        perform(multipart("/api/projects/" + key + "/import")
                .file(new MockMultipartFile("file", "x.csv", "text/csv", "Name\nfoo\n".getBytes())), alice)
                .andExpect(status().isBadRequest());
    }

    @Test
    void avatarsAcceptOnlyRealImagesAndArePubliclyServed() throws Exception {
        Account alice = register();
        byte[] png = HexFormat.of().parseHex("89504e470d0a1a0a0000000d4948445200000001000000010806000000"
                + "1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082");
        perform(multipart("/api/profile/avatar").file(new MockMultipartFile("file", "x.png", "image/png",
                "<svg onload=alert(1)>".getBytes())), alice).andExpect(status().isBadRequest());
        JsonNode user = read(perform(multipart("/api/profile/avatar")
                .file(new MockMultipartFile("file", "me.png", "image/png", png)), alice).andExpect(status().isOk()));
        String url = user.get("avatarUrl").asText();
        assertThat(url).matches("/api/avatars/[0-9a-f]{32}\\.png");

        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("sandbox")));
        mvc.perform(get("/api/avatars/../../etc/passwd")).andExpect(status().is4xxClientError());
        perform(get("/api/auth/me"), alice).andExpect(jsonPath("$.user.avatarUrl").value(url));
    }

    @Test
    void githubWebhookLinksCommitsAndCompletesOnMerge() throws Exception {
        Account alice = register();
        String key = project(alice);
        long id = task(alice, key, "Fix login").get("id").asLong();
        task(alice, key, "Other");

        JsonNode settings = read(perform(post("/api/projects/" + key + "/github"), alice).andExpect(status().isOk()));
        String secret = settings.get("secret").asText();
        assertThat(settings.get("webhookUrl").asText()).isEqualTo("https://jira.example.test/api/integrations/github/" + key);
        perform(put("/api/projects/" + key + "/github"), alice, "{\"autoDone\":true}").andExpect(jsonPath("$.autoDone").value(true));

        String push = json.writeValueAsString(Map.of("commits", java.util.List.of(Map.of(
                "id", "abcdef1234567890", "message", "Fix " + key.toLowerCase() + "-1 null check\n\nDetails",
                "url", "https://github.com/acme/web/commit/abcdef1", "author", Map.of("username", "octocat")))));
        webhook(key, "push", push, "sha256=" + "00".repeat(32)).andExpect(status().isUnauthorized());
        webhook(key, "push", push, sign(secret, push)).andExpect(jsonPath("$.linked").value(1));
        // Redelivery does not duplicate.
        webhook(key, "push", push, sign(secret, push)).andExpect(jsonPath("$.linked").value(0));

        String pr = json.writeValueAsString(Map.of("action", "closed", "repository", Map.of("full_name", "acme/web"),
                "pull_request", Map.of("number", 12, "title", "Login fixes", "body", "Closes " + key + "-1",
                        "html_url", "https://github.com/acme/web/pull/12", "merged", true, "state", "closed",
                        "head", Map.of("ref", "fix/" + key + "-1"), "user", Map.of("login", "octocat"))));
        webhook(key, "pull_request", pr, sign(secret, pr)).andExpect(jsonPath("$.completed").value(1));

        perform(get("/api/tasks/" + id), alice).andExpect(jsonPath("$.status").value("DONE"));
        JsonNode dev = getJson("/api/tasks/" + id + "/dev", alice);
        assertThat(texts(dev, "kind")).containsExactlyInAnyOrder("COMMIT", "PULL_REQUEST");
        assertThat(dev.toString()).contains("\"state\":\"merged\"");
        assertThat(ids(getJson("/api/tasks?project=" + key + "&status=DONE", alice))).containsExactly(id);

        webhook(key, "ping", "{}", sign(secret, "{}")).andExpect(status().isOk());
        perform(get("/api/projects/" + key + "/github"), register()).andExpect(status().isNotFound());
    }

    @Test
    void pushSubscriptionsOnlyAcceptKnownPushServices() throws Exception {
        Account alice = register();
        mvc.perform(get("/api/push/key")).andExpect(jsonPath("$.publicKey").isNotEmpty());
        String keys = "\"keys\":{\"p256dh\":\"BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4\",\"auth\":\"BTBZMqHH6r4Tts7J_aSIgg\"}";
        perform(post("/api/push/subscribe"), alice, "{\"endpoint\":\"http://169.254.169.254/latest\"," + keys + "}")
                .andExpect(status().isBadRequest());
        perform(post("/api/push/subscribe"), alice, "{\"endpoint\":\"https://internal.example.com/x\"," + keys + "}")
                .andExpect(status().isBadRequest());
        perform(post("/api/push/subscribe"), alice, "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/abc\"," + keys + "}")
                .andExpect(status().isNoContent());
        perform(get("/api/profile"), alice).andExpect(jsonPath("$.pushDevices").value(1));
    }

    private org.springframework.test.web.servlet.ResultActions webhook(String key, String event, String body, String signature)
            throws Exception {
        return mvc.perform(post("/api/integrations/github/" + key)
                .header("X-GitHub-Event", event)
                .header("X-Hub-Signature-256", signature)
                .contentType("application/json")
                .content(body));
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
