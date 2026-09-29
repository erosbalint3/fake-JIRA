package com.fakejira;

import com.fakejira.config.H2TcpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An existing password-less file database gets SPRING_DATASOURCE_PASSWORD on start, and APP_DB_TCP_PORT
 * then serves the live database over the network — only with that password.
 */
@SpringBootTest
class DatabaseTcpTest {

    private static final String PASSWORD = "db-Secret-123";
    private static Path dir;
    private static int port;

    @BeforeAll
    static void createLegacyDatabase() throws Exception {
        dir = Files.createTempDirectory("fakejira-tcp");
        // Like every install before this change: a file database whose "sa" user has no password.
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + dir.resolve("fakejira"), "sa", "");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE legacy_marker(id INT)");
            s.execute("INSERT INTO legacy_marker VALUES (42)");
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + dir.resolve("fakejira"));
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("app.db-tcp.port", () -> String.valueOf(port));
    }

    @AfterAll
    static void cleanUp() throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Autowired
    private H2TcpServer server;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void remoteToolsSeeTheLiveDatabaseOnlyWithThePassword() throws Exception {
        assertThat(server.port()).isEqualTo(port);
        String remote = "jdbc:h2:tcp://localhost:" + port + "/fakejira";

        assertThatThrownBy(() -> DriverManager.getConnection(remote, "sa", "").close())
                .isInstanceOf(SQLException.class);

        // The app writes, the remote client reads the same live data (and the pre-existing rows survived).
        jdbc.update("INSERT INTO legacy_marker VALUES (7)");
        try (Connection c = DriverManager.getConnection(remote, "sa", PASSWORD);
             ResultSet rows = c.createStatement().executeQuery("SELECT COUNT(*) FROM legacy_marker")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(2);
        }

        // Remote clients cannot create new databases on the server.
        assertThatThrownBy(() -> DriverManager.getConnection("jdbc:h2:tcp://localhost:" + port + "/other", "sa",
                PASSWORD).close()).isInstanceOf(SQLException.class);
    }
}
