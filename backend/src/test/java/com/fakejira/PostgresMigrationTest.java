package com.fakejira;

import com.fakejira.admin.BackupService;
import com.fasterxml.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Fills the (H2) test database through the API, then starts a second app on a real PostgreSQL server with
 * APP_MIGRATE_FROM_URL pointing at the H2 database, and checks the data arrived and the app works on PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostgresMigrationTest extends ApiTestSupport {

    @Test
    void copiesEverythingIntoPostgresAndKeepsWorking() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long sprint = read(perform(post("/api/projects/" + key + "/sprints"), alice, "{}")).get("id").asLong();
        long parent = task(alice, key, "Parent with ‘quotes’ and 'apostrophes'", "labels", List.of("ui", "urgent"),
                "sprintId", sprint, "storyPoints", 5, "checklist", List.of("One", "Two")).get("id").asLong();
        task(alice, key, "A subtask", "parentId", parent);
        perform(post("/api/tasks/" + parent + "/comments"), bob, body("body", "Looks good\nsecond line"));
        perform(post("/api/sprints/" + sprint + "/start"), alice, "{}");
        perform(patch("/api/tasks/" + parent + "/status"), alice, body("status", "IN_PROGRESS"));
        long a = read(perform(post("/api/projects/" + key + "/epics"), alice, body("name", "Auth"))).get("id").asLong();
        long b = read(perform(post("/api/projects/" + key + "/epics"), alice, body("name", "Billing"))).get("id").asLong();
        perform(put("/api/epics/" + b + "/dependencies"), alice, "{\"dependsOn\":[" + a + "]}");

        Path dir = Files.createTempDirectory("pg-migration");
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            String url = pg.getJdbcUrl("postgres", "postgres");
            ConfigurableApplicationContext app = new SpringApplicationBuilder(FakeJiraApplication.class).run(
                    "--server.port=0",
                    "--spring.datasource.url=" + url,
                    "--spring.datasource.username=postgres",
                    "--spring.datasource.password=postgres",
                    "--spring.jpa.hibernate.ddl-auto=update",
                    "--app.migrate-from.url=jdbc:h2:mem:fakejira-test;DB_CLOSE_DELAY=-1",
                    "--app.migrate-from.username=sa",
                    "--app.storage.dir=" + dir.resolve("attachments"),
                    "--app.backup.dir=" + dir.resolve("backups"),
                    "--app.jwt.secret=another-secret-another-secret-12345",
                    "--app.rate-limit.enabled=false");
            try {
                JdbcTemplate target = app.getBean(JdbcTemplate.class);
                assertThat(target.queryForObject("select count(*) from tasks", Integer.class))
                        .isEqualTo(jdbc().queryForObject("select count(*) from tasks", Integer.class));
                assertThat(target.queryForObject("select count(*) from users", Integer.class))
                        .isEqualTo(jdbc().queryForObject("select count(*) from users", Integer.class));
                assertThat(target.queryForObject("select count(*) from epic_dependencies", Integer.class)).isPositive();
                assertThat(target.queryForObject("select title from tasks where id = ?", String.class, parent))
                        .isEqualTo("Parent with ‘quotes’ and 'apostrophes'");

                // Sign in on the PostgreSQL-backed app with the copied account and keep working.
                int port = ((WebServerApplicationContext) app).getWebServer().getPort();
                HttpClient http = HttpClient.newHttpClient();
                String login = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body("login", alice.username(), "password", PASSWORD))).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                String token = json.readTree(login).get("token").asText();
                JsonNode tasks = json.readTree(get(http, port, token, "/api/tasks?project=" + key));
                assertThat(tasks).hasSize(2);
                JsonNode copied = json.readTree(get(http, port, token, "/api/tasks/" + parent));
                assertThat(copied.get("labels").toString()).contains("ui", "urgent");
                assertThat(copied.get("checklistTotal").asInt()).isEqualTo(2);
                assertThat(copied.get("subtaskTotal").asInt()).isEqualTo(1);
                assertThat(copied.get("status").asText()).isEqualTo("IN_PROGRESS");
                // Sequences continue after the copied ids, and search works on PostgreSQL.
                HttpResponse<String> created = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/tasks"))
                        .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of(
                                "projectKey", key, "title", "Made on PostgreSQL", "priority", "HIGH")))).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(created.statusCode()).isEqualTo(201);
                JsonNode found = json.readTree(get(http, port, token,
                        "/api/search?q=" + java.net.URLEncoder.encode("project = " + key + " AND (text ~ \"second line\" OR priority >= high) ORDER BY key",
                                java.nio.charset.StandardCharsets.UTF_8)));
                assertThat(found.get("total").asInt()).isEqualTo(2);
                assertThat(json.readTree(get(http, port, token, "/api/sprints/" + sprint + "/burndown")).get("total").asInt()).isEqualTo(2);
                assertThat(json.readTree(get(http, port, token, "/api/search/text?q=looks")).size()).isEqualTo(1);

                // Backups on PostgreSQL contain a SQL dump.
                BackupService.BackupFile backup = app.getBean(BackupService.class).create();
                try (ZipFile zip = new ZipFile(dir.resolve("backups").resolve(backup.name()).toFile())) {
                    String sql = new String(zip.getInputStream(zip.getEntry("database.sql")).readAllBytes());
                    assertThat(sql).contains("INSERT INTO tasks").contains("Made on PostgreSQL").contains("setval(");
                }
            } finally {
                app.close();
            }
        }
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:fakejira-test;DB_CLOSE_DELAY=-1", "sa", ""));
    }

    private static String get(HttpClient http, int port, String token, String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " → " + response.body()).isEqualTo(200);
        return response.body();
    }
}
