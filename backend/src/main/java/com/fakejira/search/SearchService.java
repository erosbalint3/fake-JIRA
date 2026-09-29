package com.fakejira.search;

import com.fakejira.project.Project;
import com.fakejira.project.ProjectRepository;
import com.fakejira.search.Fql.FqlException;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.team.Team;
import com.fakejira.team.TeamRepository;
import com.fakejira.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Runs FQL queries (for search, filters, dashboards) and the free-text search box. */
@Service
@Transactional(readOnly = true)
public class SearchService {

    /** Most tasks one query returns; the total is still reported. */
    public static final int MAX_RESULTS = 500;

    private final TaskRepository tasks;
    private final TeamRepository teams;
    private final ProjectRepository projects;
    private final EntityManager em;

    private final com.fakejira.field.CustomFieldRepository customFields;

    public SearchService(TaskRepository tasks, TeamRepository teams, ProjectRepository projects, EntityManager em,
                         com.fakejira.field.CustomFieldRepository customFields) {
        this.customFields = customFields;
        this.tasks = tasks;
        this.teams = teams;
        this.projects = projects;
        this.em = em;
    }

    public record Result(List<Task> tasks, int total) {
    }

    /** Tasks visible to {@code user} matching the query, sorted, at most {@code limit}. Throws {@link FqlException}. */
    public Result run(User user, String fql, int limit) {
        Fql.Query query = Fql.parse(fql);
        List<Long> projectIds = projects.findForMember(user.getId()).stream().map(Project::getId).toList();
        FqlCompiler compiler = new FqlCompiler(user, ZoneId.systemDefault(), this::teamMemberIds,
                name -> !projectIds.isEmpty() && customFields.existsNamed(name, projectIds));
        Specification<Task> spec = visibleTo(user).and(compiler.where(query.where()));
        Comparator<Task> order = compiler.order(query.order());
        List<Task> found = new ArrayList<>(tasks.findAll(spec));
        found.sort(order);
        int max = Math.max(1, Math.min(MAX_RESULTS, limit));
        return new Result(found.size() > max ? found.subList(0, max) : found, found.size());
    }

    /** Only tasks in projects the user belongs to. */
    public static Specification<Task> visibleTo(User user) {
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<Project> project = sub.from(Project.class);
            sub.select(project.get("id")).where(cb.equal(project, root.get("project")),
                    cb.equal(project.join("members").get("id"), user.getId()));
            return cb.exists(sub);
        };
    }

    Set<Long> teamMemberIds(String handle) {
        return teams.findByHandleIgnoreCase(handle.replaceFirst("^@", "").trim())
                .map(Team::getMembers).map(m -> m.stream().map(User::getId).collect(Collectors.toSet()))
                .orElse(null);
    }

    // ------------------------------------------------------------------ free text

    public enum HitKind { TASK, COMMENT, ATTACHMENT, EPIC, RELEASE }

    public record Hit(HitKind kind, Long id, Long taskId, String key, String title, String snippet, String projectKey,
                      int score) {
    }

    /** Every word must appear. Tasks match on key, title or description; also comments, file names, epics, releases. */
    @SuppressWarnings("unchecked")
    public List<Hit> text(User user, String q, int limit) {
        List<String> words = java.util.Arrays.stream(q.toLowerCase(Locale.ROOT).trim().split("\\s+"))
                .filter(w -> !w.isBlank()).limit(6).toList();
        if (words.isEmpty()) {
            return List.of();
        }
        List<Long> projectIds = projects.findForMember(user.getId()).stream().map(Project::getId).toList();
        if (projectIds.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        String first = "%" + escape(words.get(0)) + "%";

        List<Task> taskMatches = em.createQuery("select t from Task t where t.project.id in :projects and ("
                        + "lower(t.title) like :w escape '\\' or lower(t.description) like :w escape '\\' "
                        + "or lower(concat(t.project.key, '-', cast(t.number as string))) like :w escape '\\')", Task.class)
                .setParameter("projects", projectIds).setParameter("w", first).setMaxResults(400).getResultList();
        for (Task t : taskMatches) {
            String key = t.getKey().toLowerCase(Locale.ROOT);
            String title = t.getTitle().toLowerCase(Locale.ROOT);
            String haystack = key + " " + title + " " + t.getDescription().toLowerCase(Locale.ROOT);
            if (words.stream().allMatch(haystack::contains)) {
                int score = words.stream().allMatch(title::contains) ? 3 : 1;
                if (words.size() == 1 && key.equals(words.get(0))) {
                    score = 10;
                }
                hits.add(new Hit(HitKind.TASK, t.getId(), t.getId(), t.getKey(), t.getTitle(),
                        score >= 3 ? null : snippet(t.getDescription(), words), t.getProject().getKey(), score));
            }
        }

        List<Object[]> comments = em.createQuery("select c.id, c.body, t from Comment c join c.task t "
                        + "where t.project.id in :projects and lower(c.body) like :w escape '\\' order by c.createdAt desc")
                .setParameter("projects", projectIds).setParameter("w", first).setMaxResults(200).getResultList();
        for (Object[] row : comments) {
            String body = (String) row[1];
            Task t = (Task) row[2];
            if (words.stream().allMatch(body.toLowerCase(Locale.ROOT)::contains)) {
                hits.add(new Hit(HitKind.COMMENT, (Long) row[0], t.getId(), t.getKey(), t.getTitle(), snippet(body, words),
                        t.getProject().getKey(), 0));
            }
        }

        List<Object[]> files = em.createQuery("select a.id, a.filename, t from Attachment a join a.task t "
                        + "where t.project.id in :projects and lower(a.filename) like :w escape '\\'")
                .setParameter("projects", projectIds).setParameter("w", first).setMaxResults(100).getResultList();
        for (Object[] row : files) {
            String name = (String) row[1];
            Task t = (Task) row[2];
            if (words.stream().allMatch(name.toLowerCase(Locale.ROOT)::contains)) {
                hits.add(new Hit(HitKind.ATTACHMENT, (Long) row[0], t.getId(), t.getKey(), t.getTitle(), name,
                        t.getProject().getKey(), 0));
            }
        }

        List<Object[]> epics = em.createQuery("select e.id, e.name, e.description, e.project.key from Epic e "
                        + "where e.project.id in :projects and lower(e.name) like :w escape '\\'")
                .setParameter("projects", projectIds).setParameter("w", first).setMaxResults(50).getResultList();
        for (Object[] row : epics) {
            String name = (String) row[1];
            if (words.stream().allMatch(name.toLowerCase(Locale.ROOT)::contains)) {
                hits.add(new Hit(HitKind.EPIC, (Long) row[0], null, null, name, snippet((String) row[2], words),
                        (String) row[3], 2));
            }
        }

        List<Object[]> releases = em.createQuery("select r.id, r.name, r.description, r.project.key from Release r "
                        + "where r.project.id in :projects and lower(r.name) like :w escape '\\'")
                .setParameter("projects", projectIds).setParameter("w", first).setMaxResults(50).getResultList();
        for (Object[] row : releases) {
            String name = (String) row[1];
            if (words.stream().allMatch(name.toLowerCase(Locale.ROOT)::contains)) {
                hits.add(new Hit(HitKind.RELEASE, (Long) row[0], null, null, name, snippet((String) row[2], words),
                        (String) row[3], 2));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed());
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    /** About 140 characters around the first matching word, with ellipses. */
    static String snippet(String text, List<String> words) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        String lower = flat.toLowerCase(Locale.ROOT);
        int at = words.stream().mapToInt(lower::indexOf).filter(i -> i >= 0).min().orElse(-1);
        if (at < 0) {
            return flat.length() > 140 ? flat.substring(0, 140) + "…" : flat;
        }
        int start = Math.max(0, at - 50);
        int end = Math.min(flat.length(), start + 140);
        return (start > 0 ? "…" : "") + flat.substring(start, end) + (end < flat.length() ? "…" : "");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
