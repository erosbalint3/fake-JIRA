package com.fakejira.calendar;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.mail.MailService;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectRepository;
import com.fakejira.task.Task;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fakejira.user.UserSummary;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calendar of due dates, sprints, releases, epics and time off; a personal iCal feed; out-of-office settings. */
@RestController
@Validated
public class CalendarController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter ICS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter ICS_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final EntityManager em;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final MailService mail;

    public CalendarController(EntityManager em, ProjectRepository projects, UserRepository users, CurrentUser currentUser,
                              MailService mail) {
        this.em = em;
        this.projects = projects;
        this.users = users;
        this.currentUser = currentUser;
        this.mail = mail;
    }

    public enum Kind { TASK, SPRINT, RELEASE, EPIC, AWAY }

    /** {@code end} is inclusive (same as start for one-day events). */
    public record CalendarEvent(Kind kind, Long id, String title, LocalDate start, LocalDate end, String projectKey,
                                String key, String status, String priority, String type, Integer colorIndex,
                                UserSummary person) {
    }

    public record AwayRequest(LocalDate from, LocalDate until,
                              @Size(max = 200, message = "The note must be at most 200 characters") String message) {
    }

    public record FeedResponse(String url) {
    }

    @GetMapping("/api/calendar")
    @Transactional(readOnly = true)
    public List<CalendarEvent> calendar(@AuthenticationPrincipal Jwt jwt, @RequestParam LocalDate from,
                                        @RequestParam LocalDate to, @RequestParam(required = false) String project) {
        User user = currentUser.from(jwt);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 120) {
            throw ApiException.badRequest("Pick a range of at most 120 days.");
        }
        List<Project> visible = projects.findForMember(user.getId()).stream()
                .filter(p -> project == null || project.isBlank() || p.getKey().equalsIgnoreCase(project.trim())).toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        List<Long> ids = visible.stream().map(Project::getId).toList();
        List<CalendarEvent> events = new ArrayList<>();
        em.createQuery("select t from Task t where t.project.id in :ids and t.dueDate between :from and :to", Task.class)
                .setParameter("ids", ids).setParameter("from", from).setParameter("to", to).getResultList()
                .forEach(t -> events.add(new CalendarEvent(Kind.TASK, t.getId(), t.getTitle(), t.getDueDate(), t.getDueDate(),
                        t.getProject().getKey(), t.getKey(), t.getStatus().name(), t.getPriority().name(), t.getType().name(),
                        null, UserSummary.of(t.getAssignee()))));
        em.createQuery("select s from Sprint s where s.project.id in :ids and s.startDate <= :to and s.endDate >= :from",
                        com.fakejira.sprint.Sprint.class)
                .setParameter("ids", ids).setParameter("from", from).setParameter("to", to).getResultList()
                .forEach(s -> events.add(new CalendarEvent(Kind.SPRINT, s.getId(), s.getName(), s.getStartDate(), s.getEndDate(),
                        s.getProject().getKey(), null, s.getState().name(), null, null, null, null)));
        em.createQuery("select r from Release r where r.project.id in :ids and r.releaseDate between :from and :to",
                        com.fakejira.release.Release.class)
                .setParameter("ids", ids).setParameter("from", from).setParameter("to", to).getResultList()
                .forEach(r -> events.add(new CalendarEvent(Kind.RELEASE, r.getId(), r.getName(), r.getReleaseDate(),
                        r.getReleaseDate(), r.getProject().getKey(), null, r.isReleased() ? "RELEASED" : "UNRELEASED",
                        null, null, null, null)));
        em.createQuery("select e from Epic e where e.project.id in :ids and e.startDate is not null and e.dueDate is not null "
                        + "and e.startDate <= :to and e.dueDate >= :from", com.fakejira.epic.Epic.class)
                .setParameter("ids", ids).setParameter("from", from).setParameter("to", to).getResultList()
                .forEach(e -> events.add(new CalendarEvent(Kind.EPIC, e.getId(), e.getName(), e.getStartDate(), e.getDueDate(),
                        e.getProject().getKey(), null, null, null, null, e.getColorIndex(), null)));
        // Teammates' time off, once per person.
        Map<Long, User> people = new LinkedHashMap<>();
        visible.forEach(p -> p.getMembers().forEach(m -> people.putIfAbsent(m.getId(), m)));
        for (User person : people.values()) {
            if (person.getAwayUntil() != null && !person.getAwayUntil().isBefore(from)
                    && (person.getAwayFrom() == null || !person.getAwayFrom().isAfter(to))) {
                LocalDate start = person.getAwayFrom() == null ? from : person.getAwayFrom();
                events.add(new CalendarEvent(Kind.AWAY, person.getId(),
                        person.getAwayMessage() == null || person.getAwayMessage().isBlank() ? "Away" : person.getAwayMessage(),
                        start, person.getAwayUntil(), null, null, null, null, null, null, UserSummary.of(person)));
            }
        }
        events.sort(Comparator.comparing(CalendarEvent::start).thenComparing(e -> e.kind().ordinal()));
        return events;
    }

    // ------------------------------------------------------------------ out of office

    @PutMapping("/api/profile/away")
    @Transactional
    public UserSummary setAway(@AuthenticationPrincipal Jwt jwt, @jakarta.validation.Valid @RequestBody AwayRequest request) {
        User user = currentUser.from(jwt);
        if (request.until() == null) {
            throw ApiException.field("until", "Pick the last day you are away.");
        }
        if (request.from() != null && request.until().isBefore(request.from())) {
            throw ApiException.field("until", "The last day must be on or after the first day.");
        }
        if (request.until().isBefore(LocalDate.now())) {
            throw ApiException.field("until", "That day has already passed.");
        }
        String message = request.message() == null || request.message().isBlank() ? null : request.message().trim();
        user.setAway(request.from(), request.until(), message);
        users.save(user);
        return UserSummary.of(user);
    }

    @DeleteMapping("/api/profile/away")
    @Transactional
    public UserSummary clearAway(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        user.setAway(null, null, null);
        users.save(user);
        return UserSummary.of(user);
    }

    // ------------------------------------------------------------------ iCal feed

    @GetMapping("/api/profile/calendar-feed")
    @Transactional(readOnly = true)
    public FeedResponse feed(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        return new FeedResponse(user.getCalendarToken() == null ? null : feedUrl(user.getCalendarToken()));
    }

    /** Turns the feed on, or gives it a new secret URL (the old one stops working). */
    @PostMapping("/api/profile/calendar-feed")
    @Transactional
    public FeedResponse resetFeed(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        user.setCalendarToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        users.save(user);
        return new FeedResponse(feedUrl(user.getCalendarToken()));
    }

    @DeleteMapping("/api/profile/calendar-feed")
    @Transactional
    public FeedResponse disableFeed(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser.from(jwt);
        user.setCalendarToken(null);
        users.save(user);
        return new FeedResponse(null);
    }

    /** The personal feed: the owner's open tasks with due dates, sprints and planned releases of their projects. */
    @GetMapping("/api/calendar/feed/{file}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> ics(@PathVariable String file) {
        String token = file.endsWith(".ics") ? file.substring(0, file.length() - 4) : file;
        User user = token.length() < 20 ? null : users.findByCalendarToken(token)
                .filter(u -> u.getStatus() == com.fakejira.user.AccountStatus.ACTIVE).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        List<Long> ids = projects.findForMember(user.getId()).stream().map(Project::getId).toList();
        String stamp = ICS_STAMP.format(Instant.now());
        StringBuilder ics = new StringBuilder();
        line(ics, "BEGIN:VCALENDAR");
        line(ics, "VERSION:2.0");
        line(ics, "PRODID:-//FakeJIRA//Calendar//EN");
        line(ics, "CALSCALE:GREGORIAN");
        line(ics, "X-WR-CALNAME:FakeJIRA · " + escape(user.getName()));
        LocalDate since = LocalDate.now().minusDays(30);
        if (!ids.isEmpty()) {
            for (Task t : em.createQuery("select t from Task t where t.project.id in :ids and t.assignee.id = :me "
                            + "and t.dueDate >= :since", Task.class)
                    .setParameter("ids", ids).setParameter("me", user.getId()).setParameter("since", since).getResultList()) {
                event(ics, "task-" + t.getId(), stamp, t.getDueDate(), t.getDueDate(),
                        (t.getStatus() == TaskStatus.DONE ? "✓ " : "") + t.getKey() + " " + t.getTitle(),
                        t.getStatus().label() + " · " + t.getPriority().label() + " priority", mail.link("/tasks/" + t.getId()));
            }
            for (var s : em.createQuery("select s from Sprint s where s.project.id in :ids and s.endDate >= :since "
                            + "and s.startDate is not null", com.fakejira.sprint.Sprint.class)
                    .setParameter("ids", ids).setParameter("since", since).getResultList()) {
                event(ics, "sprint-" + s.getId(), stamp, s.getStartDate(), s.getEndDate(), s.getName(),
                        s.getGoal() == null ? "" : s.getGoal(), mail.link("/p/" + s.getProject().getKey() + "/backlog"));
            }
            for (var r : em.createQuery("select r from Release r where r.project.id in :ids and r.releaseDate >= :since",
                            com.fakejira.release.Release.class)
                    .setParameter("ids", ids).setParameter("since", since).getResultList()) {
                event(ics, "release-" + r.getId(), stamp, r.getReleaseDate(), r.getReleaseDate(),
                        r.getProject().getKey() + " " + r.getName() + (r.isReleased() ? " (released)" : " release"),
                        r.getDescription(), mail.link("/p/" + r.getProject().getKey() + "/releases"));
            }
        }
        line(ics, "END:VCALENDAR");
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ics.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String feedUrl(String token) {
        return mail.link("/api/calendar/feed/" + token + ".ics");
    }

    private static void event(StringBuilder ics, String uid, String stamp, LocalDate start, LocalDate end, String summary,
                              String description, String url) {
        line(ics, "BEGIN:VEVENT");
        line(ics, "UID:" + uid + "@fakejira");
        line(ics, "DTSTAMP:" + stamp);
        line(ics, "DTSTART;VALUE=DATE:" + ICS_DATE.format(start));
        line(ics, "DTEND;VALUE=DATE:" + ICS_DATE.format((end == null ? start : end).plusDays(1)));
        line(ics, "SUMMARY:" + escape(summary));
        if (description != null && !description.isBlank()) {
            line(ics, "DESCRIPTION:" + escape(description));
        }
        line(ics, "URL:" + url);
        line(ics, "END:VEVENT");
    }

    /** RFC 5545 text escaping. */
    static String escape(String text) {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n");
    }

    /** Folds lines longer than 75 bytes (continuation lines start with a space) and ends them with CRLF. */
    static void line(StringBuilder out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 75) {
            out.append(text).append("\r\n");
            return;
        }
        int count = 0;
        int limit = 75;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (count + size > limit) {
                out.append(current).append("\r\n ");
                current.setLength(0);
                count = 0;
                limit = 74;
            }
            current.appendCodePoint(cp);
            count += size;
            i += Character.charCount(cp);
        }
        out.append(current).append("\r\n");
    }
}
