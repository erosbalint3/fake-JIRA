package com.fakejira.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Upgrades user data from earlier versions: makes the oldest account an admin when there is
 * none yet, and turns the v2.1 email on/off switch into the instant email frequency.
 */
@Component
@Order(0)
public class UserDataMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserDataMigration.class);

    private final UserRepository users;

    public UserDataMigration(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<User> all = users.findAllByOrderByCreatedAtAsc();
        if (!all.isEmpty() && users.countByAdminTrue() == 0) {
            all.get(0).setAdmin(true);
            log.info("Made {} the first admin", all.get(0).getUsername());
        }
        for (User user : all) {
            if (user.isEmailNotifications()) {
                user.setEmailFrequency(EmailFrequency.INSTANT);
                user.setEmailNotifications(false);
            }
        }
    }
}
