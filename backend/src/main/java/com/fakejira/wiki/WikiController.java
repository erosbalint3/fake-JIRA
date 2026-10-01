package com.fakejira.wiki;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.project.ProjectDeleting;
import com.fakejira.task.Task;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Project wiki: Markdown pages in a tree, with full history and optimistic locking on save. */
@RestController
@Transactional
public class WikiController {

    static final int MAX_PAGES = 500;

    private final WikiPageRepository pages;
    private final WikiRevisionRepository revisions;
    private final ProjectAccess access;
    private final TaskSupport taskSupport;
    private final CurrentUser currentUser;
    private final LiveEvents live;

    public WikiController(WikiPageRepository pages, WikiRevisionRepository revisions, ProjectAccess access,
                          TaskSupport taskSupport, CurrentUser currentUser, LiveEvents live) {
        this.pages = pages;
        this.revisions = revisions;
        this.access = access;
        this.taskSupport = taskSupport;
        this.currentUser = currentUser;
        this.live = live;
    }

    public record PageRequest(
            @NotBlank(message = "Give the page a title") @Size(max = 120, message = "At most 120 characters") String title,
            @Size(max = WikiPage.MAX_BODY, message = "Pages are at most 100,000 characters") String body,
            Long parentId,
            /** The version the editor started from; a newer saved version means someone else saved first. */
            Integer baseVersion) {
    }

    public record PageSummary(Long id, String title, String slug, Long parentId, UserSummary updatedBy, Instant updatedAt) {
        static PageSummary of(WikiPage p) {
            return new PageSummary(p.getId(), p.getTitle(), p.getSlug(), p.getParent() == null ? null : p.getParent().getId(),
                    UserSummary.of(p.getUpdatedBy()), p.getUpdatedAt());
        }
    }

    public record PageResponse(Long id, String projectKey, String title, String slug, String body, int version,
                               Long parentId, List<PageSummary> path, List<PageSummary> children, UserSummary createdBy,
                               Instant createdAt, UserSummary updatedBy, Instant updatedAt, boolean canEdit) {
    }

    public record RevisionResponse(int version, String title, UserSummary author, Instant createdAt, String body) {
    }

    @GetMapping("/api/projects/{key}/wiki")
    @Transactional(readOnly = true)
    public List<PageSummary> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return pages.findByProjectIdOrderByTitleAsc(project.getId()).stream().map(PageSummary::of).toList();
    }

    @GetMapping("/api/projects/{key}/wiki/{slug}")
    @Transactional(readOnly = true)
    public PageResponse bySlug(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @PathVariable String slug) {
        User user = currentUser.from(jwt);
        Project project = access.memberProject(key, user);
        WikiPage page = pages.findByProjectIdAndSlug(project.getId(), slug)
                .orElseThrow(() -> ApiException.notFound("That page does not exist."));
        return response(page, user);
    }

    @PostMapping("/api/projects/{key}/wiki")
    @ResponseStatus(HttpStatus.CREATED)
    public PageResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String key, @Valid @RequestBody PageRequest request) {
        User user = currentUser.from(jwt);
        Project project = access.editorProject(key, user);
        if (pages.findByProjectIdOrderByTitleAsc(project.getId()).size() >= MAX_PAGES) {
            throw ApiException.badRequest("A wiki can have at most " + MAX_PAGES + " pages.");
        }
        String title = request.title().trim();
        WikiPage page = new WikiPage(project, title, uniqueSlug(project, title, null), body(request), user);
        page.setParent(parent(project, request.parentId(), null));
        pages.save(page);
        revisions.save(new WikiRevision(page));
        live.projectChanged(project);
        return response(page, user);
    }

    @PutMapping("/api/wiki/{id}")
    public PageResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody PageRequest request) {
        User user = currentUser.from(jwt);
        WikiPage page = editable(id, user);
        if (request.baseVersion() != null && request.baseVersion() != page.getVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, page.getUpdatedBy().getUsername()
                    + " saved a newer version while you were editing.", java.util.Map.of("version", String.valueOf(page.getVersion())));
        }
        String title = request.title().trim();
        String body = body(request);
        page.setParent(parent(page.getProject(), request.parentId(), page));
        if (!title.equals(page.getTitle()) || !body.equals(page.getBody())) {
            if (!title.equals(page.getTitle())) {
                page.setSlug(uniqueSlug(page.getProject(), title, page.getId()));
            }
            page.revise(title, body, user);
            revisions.save(new WikiRevision(page));
        }
        live.projectChanged(page.getProject());
        return response(page, user);
    }

    @DeleteMapping("/api/wiki/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        WikiPage page = editable(id, currentUser.from(jwt));
        // Sub-pages move up a level rather than disappearing.
        for (WikiPage child : pages.findByParentId(page.getId())) {
            child.setParent(page.getParent());
        }
        revisions.deleteForPage(page.getId());
        pages.delete(page);
        live.projectChanged(page.getProject());
    }

    @GetMapping("/api/wiki/{id}/history")
    @Transactional(readOnly = true)
    public List<RevisionResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        visible(id, currentUser.from(jwt));
        return revisions.history(id).stream()
                .map(r -> new RevisionResponse(r.getVersion(), r.getTitle(), UserSummary.of(r.getAuthor()), r.getCreatedAt(), null))
                .toList();
    }

    @GetMapping("/api/wiki/{id}/history/{version}")
    @Transactional(readOnly = true)
    public RevisionResponse revision(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable int version) {
        visible(id, currentUser.from(jwt));
        WikiRevision r = revisions.findByPageIdAndVersion(id, version)
                .orElseThrow(() -> ApiException.notFound("That version does not exist."));
        return new RevisionResponse(r.getVersion(), r.getTitle(), UserSummary.of(r.getAuthor()), r.getCreatedAt(), r.getBody());
    }

    /** Restoring saves the old content as a new version, so nothing is lost. */
    @PostMapping("/api/wiki/{id}/history/{version}/restore")
    public PageResponse restore(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable int version) {
        User user = currentUser.from(jwt);
        WikiPage page = editable(id, user);
        WikiRevision r = revisions.findByPageIdAndVersion(id, version)
                .orElseThrow(() -> ApiException.notFound("That version does not exist."));
        if (!r.getTitle().equals(page.getTitle())) {
            page.setSlug(uniqueSlug(page.getProject(), r.getTitle(), page.getId()));
        }
        page.revise(r.getTitle(), r.getBody(), user);
        revisions.save(new WikiRevision(page));
        live.projectChanged(page.getProject());
        return response(page, user);
    }

    /** Wiki pages that mention a task's key. */
    @GetMapping("/api/tasks/{id}/wiki")
    @Transactional(readOnly = true)
    public List<PageSummary> mentioning(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Task task = taskSupport.memberTask(id, currentUser.from(jwt));
        String key = task.getKey().toUpperCase(Locale.ROOT);
        return pages.mentioning(task.getProject().getId(), "%" + key + "%").stream()
                // "WEB-1" must not match "WEB-12".
                .filter(p -> p.getBody().toUpperCase(Locale.ROOT).matches("(?s).*\\b" + java.util.regex.Pattern.quote(key) + "(?![0-9]).*"))
                .map(PageSummary::of).toList();
    }

    private PageResponse response(WikiPage page, User user) {
        List<PageSummary> path = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (WikiPage p = page.getParent(); p != null && seen.add(p.getId()); p = p.getParent()) {
            path.add(0, PageSummary.of(p));
        }
        List<PageSummary> children = pages.findByParentId(page.getId()).stream().map(PageSummary::of)
                .sorted(java.util.Comparator.comparing(PageSummary::title, String.CASE_INSENSITIVE_ORDER)).toList();
        return new PageResponse(page.getId(), page.getProject().getKey(), page.getTitle(), page.getSlug(), page.getBody(),
                page.getVersion(), page.getParent() == null ? null : page.getParent().getId(), path, children,
                UserSummary.of(page.getCreatedBy()), page.getCreatedAt(), UserSummary.of(page.getUpdatedBy()),
                page.getUpdatedAt(), page.getProject().canEdit(user));
    }

    private WikiPage parent(Project project, Long parentId, WikiPage self) {
        if (parentId == null) {
            return null;
        }
        WikiPage parent = pages.findById(parentId).filter(p -> p.getProject().getId().equals(project.getId()))
                .orElseThrow(() -> ApiException.badRequest("That parent page is not in " + project.getKey() + "."));
        for (WikiPage p = parent; p != null; p = p.getParent()) {
            if (self != null && p.getId().equals(self.getId())) {
                throw ApiException.badRequest("A page cannot be moved under itself.");
            }
        }
        return parent;
    }

    private String uniqueSlug(Project project, String title, Long self) {
        String base = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "page";
        }
        base = base.length() > 80 ? base.substring(0, 80) : base;
        String slug = base;
        for (int i = 2; ; i++) {
            var existing = pages.findByProjectIdAndSlug(project.getId(), slug);
            if (existing.isEmpty() || existing.get().getId().equals(self)) {
                return slug;
            }
            slug = base + "-" + i;
        }
    }

    private static String body(PageRequest request) {
        return request.body() == null ? "" : request.body().replace("\r\n", "\n");
    }

    private WikiPage visible(Long id, User user) {
        WikiPage page = pages.findById(id).orElseThrow(() -> ApiException.notFound("That page does not exist."));
        if (!page.getProject().hasMember(user)) {
            throw ApiException.notFound("That page does not exist.");
        }
        return page;
    }

    private WikiPage editable(Long id, User user) {
        WikiPage page = visible(id, user);
        access.requireEditor(page.getProject(), user);
        return page;
    }

    @EventListener
    public void onProjectDeleting(ProjectDeleting event) {
        List<WikiPage> list = pages.findByProjectIdOrderByTitleAsc(event.projectId());
        list.forEach(p -> p.setParent(null));
        pages.flush();
        list.forEach(p -> revisions.deleteForPage(p.getId()));
        pages.deleteAll(list);
    }
}
