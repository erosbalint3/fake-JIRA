package com.fakejira;

import com.fakejira.admin.OffsiteBackupAccess;
import com.fakejira.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Off-site backups, health and metrics, quotas, custom fields, project templates and the language setting. */
@SpringBootTest
@AutoConfigureMockMvc
class OpsAndPolishTest extends ApiTestSupport {

    record Upload(String method, String path, String authorization, int bytes) {
    }

    static final List<Upload> UPLOADS = new CopyOnWriteArrayList<>();
    static final HttpServer STORAGE = start();

    static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                UPLOADS.add(new Upload(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), body.length));
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().contains("broken") ? 500 : 201, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stop() {
        STORAGE.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + STORAGE.getAddress().getPort();
        registry.add("app.backup.s3.endpoint", () -> base);
        registry.add("app.backup.s3.bucket", () -> "backups");
        registry.add("app.backup.s3.access-key", () -> "AKIDEXAMPLE");
        registry.add("app.backup.s3.secret-key", () -> "secret");
        registry.add("app.backup.s3.region", () -> "eu-central-1");
        registry.add("app.backup.webdav.url", () -> base + "/dav/Backups/");
        registry.add("app.backup.webdav.username", () -> "nas");
        registry.add("app.backup.webdav.password", () -> "pw");
        registry.add("app.metrics.token", () -> "metrics-secret");
        registry.add("app.backup.dir", () -> System.getProperty("java.io.tmpdir") + "/fj-ops-backups-" + ProcessHandle.current().pid());
    }

    @Autowired
    private UserRepository users;

    private Account admin() throws Exception {
        Account account = register();
        users.findById(account.id()).ifPresent(u -> {
            u.setAdmin(true);
            users.save(u);
        });
        return account;
    }

    @Test
    void sigV4MatchesAwsExample() {
        // From the AWS S3 documentation ("GET Object" example of header-based SigV4 authentication).
        Map<String, String> headers = new TreeMap<>(Map.of(
                "host", "examplebucket.s3.amazonaws.com",
                "range", "bytes=0-9",
                "x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "x-amz-date", "20130524T000000Z"));
        assertThat(OffsiteBackupAccess.signature("GET", "/test.txt", "", headers,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "20130524T000000Z", "us-east-1", "s3",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"))
                .isEqualTo("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void backupsAreCopiedOffsiteAndHealthAndMetricsWork() throws Exception {
        Account admin = admin();
        Account someone = register();
        perform(post("/api/admin/offsite/upload"), someone).andExpect(status().isForbidden());
        JsonNode offsite = read(perform(post("/api/admin/offsite/upload"), admin).andExpect(status().isOk()));
        assertThat(offsite.get("lastError").isNull()).isTrue();
        assertThat(offsite.get("s3").asBoolean()).isTrue();
        Upload s3 = UPLOADS.stream().filter(u -> u.path().startsWith("/backups/fakejira/fakejira-backup-")).findFirst().orElseThrow();
        assertThat(s3.method()).isEqualTo("PUT");
        assertThat(s3.authorization()).startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/").contains("/eu-central-1/s3/aws4_request")
                .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date");
        assertThat(s3.bytes()).isPositive();
        Upload dav = UPLOADS.stream().filter(u -> u.path().startsWith("/dav/Backups/fakejira-backup-")).findFirst().orElseThrow();
        assertThat(dav.authorization()).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("nas:pw".getBytes()));

        mvc.perform(get("/api/health")).andExpect(jsonPath("$.status").value("UP"));
        JsonNode system = getJson("/api/admin/system", admin);
        assertThat(system.get("database").get("ok").asBoolean()).isTrue();
        assertThat(system.get("counts").get("users").asInt()).isPositive();
        assertThat(system.get("features").get("offsiteBackups").asBoolean()).isTrue();
        perform(get("/api/admin/system"), someone).andExpect(status().isForbidden());

        mvc.perform(get("/api/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/metrics").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        String metrics = mvc.perform(get("/api/metrics").header("Authorization", "Bearer metrics-secret"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(metrics).contains("# TYPE fakejira_users gauge").contains("fakejira_tasks{state=\"open\"}")
                .contains("fakejira_http_requests_total{method=\"GET\",status=\"2xx\"}").contains("fakejira_offsite_backup_ok 1");
    }

    @Test
    void attachmentQuotasAreEnforced() throws Exception {
        Account admin = admin();
        String key = project(admin);
        long id = task(admin, key, "Files").get("id").asLong();
        perform(put("/api/admin/quotas"), admin, "{\"projectMb\":1,\"totalMb\":0}").andExpect(jsonPath("$.projectMb").value(1));
        byte[] small = new byte[600 * 1024];
        mvc.perform(multipart("/api/tasks/" + id + "/attachments").file(new MockMultipartFile("file", "a.bin", "application/octet-stream", small))
                .header("Authorization", "Bearer " + admin.token())).andExpect(status().isCreated());
        mvc.perform(multipart("/api/tasks/" + id + "/attachments").file(new MockMultipartFile("file", "b.bin", "application/octet-stream", small))
                .header("Authorization", "Bearer " + admin.token())).andExpect(status().isPayloadTooLarge());
        JsonNode usage = getJson("/api/projects/" + key + "/storage", admin);
        assertThat(usage.get("usedBytes").asLong()).isEqualTo(small.length);
        assertThat(usage.get("quotaBytes").asLong()).isEqualTo(1024 * 1024);
        perform(put("/api/admin/quotas"), admin, "{\"projectMb\":0,\"totalMb\":0}");
    }

    @Test
    void customFieldsAreTypedSearchableAndExported() throws Exception {
        Account alice = register();
        String key = project(alice);
        JsonNode severity = read(perform(post("/api/projects/" + key + "/fields"), alice,
                "{\"name\":\"Severity\",\"type\":\"SELECT\",\"options\":[\"Blocker\",\"Major\",\"Minor\"]}").andExpect(status().isCreated()));
        JsonNode customer = read(perform(post("/api/projects/" + key + "/fields"), alice,
                "{\"name\":\"Customer name\",\"type\":\"TEXT\"}"));
        JsonNode due = read(perform(post("/api/projects/" + key + "/fields"), alice, "{\"name\":\"Deadline\",\"type\":\"DATE\"}"));
        perform(post("/api/projects/" + key + "/fields"), alice, "{\"name\":\"severity\",\"type\":\"TEXT\"}").andExpect(status().isBadRequest());
        perform(post("/api/projects/" + key + "/fields"), alice, "{\"name\":\"Empty\",\"type\":\"SELECT\"}").andExpect(status().isBadRequest());

        long a = task(alice, key, "Crash").get("id").asLong();
        long b = task(alice, key, "Typo").get("id").asLong();
        perform(put("/api/tasks/" + a + "/fields/" + severity.get("id").asLong()), alice, "{\"value\":\"major\"}")
                .andExpect(jsonPath("$.value").value("Major"));
        perform(put("/api/tasks/" + a + "/fields/" + severity.get("id").asLong()), alice, "{\"value\":\"Huge\"}")
                .andExpect(status().isBadRequest());
        perform(put("/api/tasks/" + a + "/fields/" + customer.get("id").asLong()), alice, "{\"value\":\"Acme Corp\"}");
        perform(put("/api/tasks/" + b + "/fields/" + due.get("id").asLong()), alice, "{\"value\":\"not a date\"}").andExpect(status().isBadRequest());
        perform(put("/api/tasks/" + b + "/fields/" + severity.get("id").asLong()), alice, "{\"value\":\"Minor\"}");

        JsonNode values = getJson("/api/tasks/" + a + "/fields", alice);
        assertThat(texts(values, "value")).contains("Major", "Acme Corp");
        assertThat(getJson("/api/tasks/" + a + "/activity", alice).toString()).contains("set Severity to Major");

        assertThat(texts(read(perform(get("/api/search").param("q", "project = " + key + " AND severity = major"), alice)).get("tasks"), "title"))
                .containsExactly("Crash");
        assertThat(texts(read(perform(get("/api/search").param("q", "project = " + key + " AND \"Customer name\" ~ acme"), alice)).get("tasks"), "title"))
                .containsExactly("Crash");
        assertThat(texts(read(perform(get("/api/search").param("q", "project = " + key + " AND \"customer name\" is empty"), alice)).get("tasks"), "title"))
                .containsExactly("Typo");

        String csv = perform(get("/api/projects/" + key + "/export.csv"), alice).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.lines().findFirst().orElseThrow()).endsWith(",Severity,Customer name,Deadline");
        assertThat(csv).contains("Major,Acme Corp,");
    }

    @Test
    void projectTemplatesSetThingsUp() throws Exception {
        Account alice = register();
        String kanban = uniqueName("K").toUpperCase().substring(0, 8);
        perform(post("/api/projects"), alice, "{\"key\":\"" + kanban + "\",\"name\":\"Flow\",\"template\":\"kanban\"}")
                .andExpect(jsonPath("$.kanban").value(true));
        JsonNode columns = getJson("/api/projects/" + kanban + "/columns", alice);
        assertThat(texts(columns, "name")).containsExactly("To do", "Doing", "Review", "Done");
        assertThat(columns.get(1).get("wipLimit").asInt()).isEqualTo(3);

        String bugs = uniqueName("B").toUpperCase().substring(0, 8);
        perform(post("/api/projects"), alice, "{\"key\":\"" + bugs + "\",\"name\":\"Bugs\",\"template\":\"bugs\"}");
        assertThat(texts(getJson("/api/projects/" + bugs + "/fields", alice), "name")).containsExactly("Severity", "Environment", "Affected version");
        assertThat(texts(getJson("/api/projects/" + bugs + "/automations", alice), "trigger")).containsExactly("CREATED", "SCHEDULED");
        assertThat(texts(getJson("/api/projects/" + bugs + "/templates", alice), "name")).contains("Bug report");
        long bug = task(alice, bugs, "Crash", "type", "BUG").get("id").asLong();
        assertThat(getJson("/api/tasks/" + bug, alice).get("labels").toString()).contains("triage");

        perform(post("/api/projects"), alice, "{\"key\":\"ZZ" + System.nanoTime() % 1000 + "\",\"name\":\"x\",\"template\":\"nope\"}")
                .andExpect(status().isBadRequest());
        assertThat(getJson("/api/project-templates", alice).size()).isEqualTo(5);
    }

    @Test
    void languagePreferenceIsSaved() throws Exception {
        Account alice = register();
        perform(put("/api/profile/settings"), alice, "{\"language\":\"hu\"}").andExpect(jsonPath("$.language").value("hu"));
        perform(get("/api/auth/me"), alice).andExpect(jsonPath("$.language").value("hu"));
        perform(put("/api/profile/settings"), alice, "{\"language\":\"xx\"}").andExpect(status().isBadRequest());
    }
}
