package com.fakejira.config;

import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.SQLException;

/**
 * Optional network access to the live H2 database for tools like DBeaver (APP_DB_TCP_PORT).
 * Off by default, and refuses to start while the database has no password.
 * Connect with jdbc:h2:tcp://HOST:PORT/fakejira (the database file name without .mv.db).
 */
@Component
public class H2TcpServer implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(H2TcpServer.class);

    private final String port;
    private final String url;
    private final String password;
    private Server server;

    public H2TcpServer(@Value("${app.db-tcp.port:}") String port,
                       @Value("${spring.datasource.url:}") String url,
                       @Value("${spring.datasource.password:}") String password) {
        this.port = port.trim();
        this.url = url;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (port.isEmpty()) {
            return;
        }
        if (!url.startsWith("jdbc:h2:file:")) {
            log.warn("APP_DB_TCP_PORT is set but the database is not an H2 file database; not starting it");
            return;
        }
        if (password.isEmpty()) {
            log.error("APP_DB_TCP_PORT is set but SPRING_DATASOURCE_PASSWORD is empty; refusing to expose a "
                    + "database without a password");
            return;
        }
        Path file = Path.of(url.substring("jdbc:h2:file:".length()).split(";")[0]).toAbsolutePath().normalize();
        try {
            // -ifExists: remote clients can only open existing databases, never create new ones.
            server = Server.createTcpServer("-tcpPort", port, "-tcpAllowOthers", "-ifExists",
                    "-baseDir", file.getParent().toString()).start();
            log.info("Database network access on port {}: jdbc:h2:tcp://<host>:{}/{}", server.getPort(),
                    server.getPort(), file.getFileName());
        } catch (SQLException e) {
            log.error("Could not start database network access on port {}: {}", port, e.getMessage());
        }
    }

    /** The port actually listening, or -1 when off. */
    public int port() {
        return server == null ? -1 : server.getPort();
    }

    @Override
    public void destroy() {
        if (server != null) {
            server.stop();
        }
    }
}
