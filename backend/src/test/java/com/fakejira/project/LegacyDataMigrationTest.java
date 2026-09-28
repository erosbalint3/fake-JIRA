package com.fakejira.project;

import com.fakejira.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class LegacyDataMigrationTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LegacyDataMigration migration;

    @Test
    void tasksWithoutProjectMoveIntoFjProjectKeepingKeysAndTimestamps() throws Exception {
        Account old = register();
        Timestamp updated = Timestamp.from(Instant.parse("2025-01-02T03:04:05Z"));
        // Rows shaped like a v2.0 database: no project, no number.
        jdbc.update("insert into tasks (title, description, priority, status, reporter_id, created_at, updated_at) "
                + "values ('Old todo', '', 'HIGH', 'TODO', ?, ?, ?)", old.id(), updated, updated);
        jdbc.update("insert into tasks (title, description, priority, status, reporter_id, created_at, updated_at) "
                + "values ('Old done', '', 'LOW', 'DONE', ?, ?, ?)", old.id(), updated, updated);
        Long todoId = jdbc.queryForObject("select id from tasks where title = 'Old todo'", Long.class);

        migration.run(null);
        migration.run(null); // idempotent

        JsonNode todo = getJson("/api/tasks/" + todoId, old);
        assertThat(todo.get("key").asText()).isEqualTo("FJ-" + todoId);
        assertThat(Instant.parse(todo.get("updatedAt").asText()).truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(updated.toInstant());
        JsonNode done = getJson("/api/tasks?project=FJ&status=DONE", old).get(0);
        assertThat(done.get("completedAt").isNull()).isFalse();

        // New tasks in FJ continue after the highest adopted number.
        long max = jdbc.queryForObject("select max(task_number) from tasks where project_id = "
                + "(select id from projects where project_key = 'FJ')", Long.class);
        assertThat(task(old, "FJ", "New after upgrade").get("key").asText()).isEqualTo("FJ-" + (max + 1));
    }
}
