package com.fakejira.personal;

import com.fakejira.project.MemberRemoved;
import com.fakejira.task.TaskDeleting;
import com.fakejira.user.AccountService;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Drops personal data (reminders, timers, Today picks, private notes) that no longer has a home. */
@Component
public class PersonalCleanup {

    static final String[] TABLES = {"reminders", "running_timers", "today_picks", "personal_notes", "private_checklist_items"};

    private final JdbcTemplate jdbc;

    public PersonalCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        for (String table : TABLES) {
            jdbc.update("delete from " + table + " where task_id = ?", event.taskId());
        }
    }

    @EventListener
    public void onMemberRemoved(MemberRemoved event) {
        for (String table : TABLES) {
            jdbc.update("delete from " + table + " where user_id = ? and task_id in (select id from tasks where project_id = ?)",
                    event.userId(), event.projectId());
        }
    }

    @EventListener
    public void onUserDeleting(AccountService.UserDeleting event) {
        for (String table : TABLES) {
            jdbc.update("delete from " + table + " where user_id = ?", event.userId());
        }
    }
}
