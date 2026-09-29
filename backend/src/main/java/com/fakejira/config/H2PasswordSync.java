package com.fakejira.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Lets SPRING_DATASOURCE_PASSWORD be set on an existing H2 file database that was created without one
 * (every FakeJIRA install before 3.1): before the connection pool starts, if the configured password is
 * rejected but the empty password works, the database user's password is changed to the configured one.
 */
@Component
public class H2PasswordSync implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(H2PasswordSync.class);
    private static final String WRONG_USER_OR_PASSWORD = "28000";

    private final Environment env;
    private boolean done;

    public H2PasswordSync(Environment env) {
        this.env = env;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource && !done) {
            done = true;
            if (bean instanceof HikariDataSource pool) {
                // The pool's own settings: what it will actually connect with.
                sync(nullToEmpty(pool.getJdbcUrl()), nullToEmpty(pool.getUsername()), nullToEmpty(pool.getPassword()));
            } else {
                sync(env.getProperty("spring.datasource.url", ""), env.getProperty("spring.datasource.username", "sa"),
                        env.getProperty("spring.datasource.password", ""));
            }
        }
        return bean;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static void sync(String url, String user, String password) {
        if (!url.startsWith("jdbc:h2:file:") || password.isEmpty()) {
            return;
        }
        // IFEXISTS: a brand-new install creates its database later, with the configured password.
        String existing = url + ";IFEXISTS=TRUE";
        try (Connection ignored = DriverManager.getConnection(existing, user, password)) {
            return;
        } catch (SQLException e) {
            if (!WRONG_USER_OR_PASSWORD.equals(e.getSQLState())) {
                return; // no database yet, or another problem the pool will report properly
            }
        }
        try (Connection connection = DriverManager.getConnection(existing, user, "");
             PreparedStatement alter = connection.prepareStatement(
                     "ALTER USER \"" + user.toUpperCase().replace("\"", "") + "\" SET PASSWORD ?")) {
            alter.setString(1, password);
            alter.execute();
            log.info("Database password set from SPRING_DATASOURCE_PASSWORD");
        } catch (SQLException e) {
            log.error("The database rejects SPRING_DATASOURCE_PASSWORD and it could not be set: {}", e.getMessage());
        }
    }
}
