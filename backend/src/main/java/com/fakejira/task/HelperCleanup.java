package com.fakejira.task;

import com.fakejira.project.MemberRemoved;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Someone who leaves a project stops helping on its tasks. */
@Component
public class HelperCleanup {

    private final JdbcTemplate jdbc;

    public HelperCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    public void onMemberRemoved(MemberRemoved event) {
        jdbc.update("delete from task_helpers where user_id = ? and task_id in (select id from tasks where project_id = ?)",
                event.userId(), event.projectId());
    }
}
