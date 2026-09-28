package com.fakejira.project;

import com.fakejira.task.TaskRepository;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Upgrades databases created before projects existed (v2.0): all existing tasks move into
 * a "FakeJIRA" project with key FJ and keep their FJ-&lt;id&gt; keys; every existing user
 * becomes a member. Safe to run on every start: it only acts on tasks without a project.
 */
@Component
public class LegacyDataMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyDataMigration.class);
    static final String LEGACY_KEY = "FJ";

    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final UserRepository users;

    public LegacyDataMigration(TaskRepository tasks, ProjectRepository projects, UserRepository users) {
        this.tasks = tasks;
        this.projects = projects;
        this.users = users;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        tasks.backfillCompletedAt();
        if (tasks.countByProjectIsNull() == 0) {
            return;
        }
        List<User> everyone = users.findAll(Sort.by("id"));
        Project project = projects.findByKey(LEGACY_KEY).orElseGet(() -> projects.save(
                new Project(LEGACY_KEY, "FakeJIRA", "Tasks created before projects were introduced.", everyone.get(0))));
        project.getMembers().addAll(everyone);
        int adopted = tasks.adoptOrphans(project);
        project.setNextNumber(Math.max(project.getNextNumber(), tasks.maxNumber(project.getId()) + 1));
        log.info("Moved {} existing task(s) into project {} with {} member(s)", adopted, LEGACY_KEY, everyone.size());
    }
}
