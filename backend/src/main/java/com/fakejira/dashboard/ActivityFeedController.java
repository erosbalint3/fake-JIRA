package com.fakejira.dashboard;

import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectRepository;
import com.fakejira.task.Comment;
import com.fakejira.task.Task;
import com.fakejira.task.TaskActivity;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.persistence.EntityManager;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** What happened lately across the caller's projects: changes and comments, newest first. */
@RestController
public class ActivityFeedController {

    private final EntityManager em;
    private final ProjectRepository projects;
    private final CurrentUser currentUser;

    public ActivityFeedController(EntityManager em, ProjectRepository projects, CurrentUser currentUser) {
        this.em = em;
        this.projects = projects;
        this.currentUser = currentUser;
    }

    public record FeedTask(Long id, String key, String title, String projectKey) {
    }

    /** {@code kind} is "change" or "comment"; {@code body} is the comment text (shortened). */
    public record FeedItem(String kind, Long id, UserSummary actor, String message, String body, Instant createdAt,
                           FeedTask task) {
    }

    /**
     * @param before only items older than this (for paging: pass the last item's createdAt)
     * @param user   only this person's actions (username)
     */
    @GetMapping("/api/activity")
    @Transactional(readOnly = true)
    public List<FeedItem> feed(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String project,
                               @RequestParam(required = false) String user, @RequestParam(required = false) Instant before,
                               @RequestParam(defaultValue = "40") int limit) {
        User me = currentUser.from(jwt);
        List<Long> ids = projects.findForMember(me.getId()).stream()
                .filter(p -> project == null || project.isBlank() || p.getKey().equalsIgnoreCase(project.trim()))
                .map(Project::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        int max = Math.max(1, Math.min(100, limit));
        Instant until = before == null ? Instant.now().plusSeconds(60) : before;
        String byUser = user == null || user.isBlank() ? null : user.trim().toLowerCase();

        var changeQuery = em.createQuery("select a from TaskActivity a join fetch a.actor join fetch a.task t "
                        + "where t.project.id in :ids and a.createdAt < :until"
                        + (byUser == null ? "" : " and lower(a.actor.username) = :user")
                        + " order by a.createdAt desc, a.id desc", TaskActivity.class)
                .setParameter("ids", ids).setParameter("until", until).setMaxResults(max);
        var commentQuery = em.createQuery("select c from Comment c join fetch c.author join fetch c.task t "
                        + "where t.project.id in :ids and c.createdAt < :until"
                        + (byUser == null ? "" : " and lower(c.author.username) = :user")
                        + " order by c.createdAt desc, c.id desc", Comment.class)
                .setParameter("ids", ids).setParameter("until", until).setMaxResults(max);
        if (byUser != null) {
            changeQuery.setParameter("user", byUser);
            commentQuery.setParameter("user", byUser);
        }
        List<TaskActivity> changes = changeQuery.getResultList();
        List<Comment> comments = commentQuery.getResultList();

        List<FeedItem> items = new ArrayList<>();
        for (TaskActivity a : changes) {
            items.add(new FeedItem("change", a.getId(), UserSummary.of(a.getActor()), a.getMessage(), null, a.getCreatedAt(),
                    task(a.getTask())));
        }
        for (Comment c : comments) {
            String body = c.getBody().length() > 280 ? c.getBody().substring(0, 277) + "…" : c.getBody();
            items.add(new FeedItem("comment", c.getId(), UserSummary.of(c.getAuthor()), "commented", body, c.getCreatedAt(),
                    task(c.getTask())));
        }
        items.sort(Comparator.comparing(FeedItem::createdAt).reversed());
        return items.size() > max ? items.subList(0, max) : items;
    }

    private static FeedTask task(Task t) {
        return new FeedTask(t.getId(), t.getKey(), t.getTitle(), t.getProject().getKey());
    }
}
