package com.fakejira.events;

import com.fakejira.project.Project;
import com.fakejira.task.Task;
import com.fakejira.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Convenience methods for broadcasting changes to everyone in a project. */
@Component
public class LiveEvents {

    private final ApplicationEventPublisher publisher;

    public LiveEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void taskChanged(Task task) {
        publish(task.getProject(), "task", Map.of("projectId", task.getProject().getId(), "taskId", task.getId()));
    }

    public void taskDeleted(Project project, Long taskId) {
        publish(project, "task", Map.of("projectId", project.getId(), "taskId", taskId, "deleted", true));
    }

    public void projectChanged(Project project) {
        publish(project, "project", Map.of("projectId", project.getId()));
    }

    /** For members who were just removed and so are no longer in the project's member list. */
    public void projectChangedFor(Project project, Set<Long> extraRecipients) {
        Set<Long> recipients = memberIds(project);
        recipients.addAll(extraRecipients);
        publisher.publishEvent(new LiveEvent(recipients, "project", Map.of("projectId", project.getId())));
    }

    private void publish(Project project, String type, Map<String, Object> data) {
        publisher.publishEvent(new LiveEvent(memberIds(project), type, data));
    }

    private static Set<Long> memberIds(Project project) {
        return project.getMembers().stream().map(User::getId).collect(Collectors.toSet());
    }
}
