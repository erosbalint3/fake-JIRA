package com.fakejira.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Moving to PostgreSQL: start the app against the new database with APP_MIGRATE_FROM_URL pointing at the old one
 * (e.g. jdbc:h2:file:./data/fakejira). On a fresh target everything is copied once; afterwards the setting is ignored.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationRunner.class);

    private final DataSource dataSource;
    private final String url;
    private final String username;
    private final String password;

    public DatabaseMigrationRunner(DataSource dataSource,
                                   @Value("${app.migrate-from.url:}") String url,
                                   @Value("${app.migrate-from.username:sa}") String username,
                                   @Value("${app.migrate-from.password:}") String password) {
        this.dataSource = dataSource;
        this.url = url.trim();
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (url.isEmpty()) {
            return;
        }
        try (Connection target = dataSource.getConnection()) {
            if (DatabaseCopier.userCount(target) > 0) {
                log.info("APP_MIGRATE_FROM_URL is set but this database already has users; nothing copied. "
                        + "You can remove the setting.");
                return;
            }
            log.info("Copying all data from {} into the new database…", url);
            try (Connection source = DriverManager.getConnection(url, username, password)) {
                DatabaseCopier.Report report = DatabaseCopier.copy(source, target);
                log.info("Database migration finished: {} rows in {} tables. You can now remove APP_MIGRATE_FROM_URL.",
                        report.total(), report.rows().size());
            }
        }
    }
}
