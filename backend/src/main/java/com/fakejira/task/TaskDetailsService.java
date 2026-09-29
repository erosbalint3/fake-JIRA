package com.fakejira.task;

import com.fakejira.common.ApiException;
import com.fakejira.events.LiveEvents;
import com.fakejira.integration.DevLinkRepository;
import com.fakejira.notification.NotificationService;
import com.fakejira.task.TaskDtos.ActivityResponse;
import com.fakejira.task.TaskDtos.ChecklistItemResponse;
import com.fakejira.task.TaskDtos.ChecklistUpdateRequest;
import com.fakejira.task.TaskDtos.CommentResponse;
import com.fakejira.task.TaskDtos.DevLinkResponse;
import com.fakejira.task.TaskDtos.LinkRequest;
import com.fakejira.task.TaskDtos.LinkResponse;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.task.TaskDtos.TimeEntryResponse;
import com.fakejira.task.TaskDtos.TimeRequest;
import com.fakejira.task.TaskDtos.WatchersResponse;
import com.fakejira.user.User;
import com.fakejira.user.UserSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Everything attached to a single task: comments, checklist, history, links, time and watchers. */
@Service
@Transactional
public class TaskDetailsService {

    private final TaskSupport support;
    private final TaskService taskService;
    private final CommentRepository comments;
    private final ChecklistItemRepository checklist;
    private final TaskActivityRepository activity;
    private final TaskLinkRepository links;
    private final TimeEntryRepository time;
    private final DevLinkRepository devLinks;
    private final NotificationService notifications;
    private final LiveEvents live;

    public TaskDetailsService(TaskSupport support, TaskService taskService, CommentRepository comments,
                              ChecklistItemRepository checklist, TaskActivityRepository activity,
                              TaskLinkRepository links, TimeEntryRepository time, DevLinkRepository devLinks,
                              NotificationService notifications, LiveEvents live) {
        this.support = support;
        this.taskService = taskService;
        this.comments = comments;
        this.checklist = checklist;
        this.activity = activity;
        this.links = links;
        this.time = time;
        this.devLinks = devLinks;
        this.notifications = notifications;
        this.live = live;
    }

    // ---------------------------------------------------------------- comments

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(User user, Long taskId) {
        support.memberTask(taskId, user);
        return comments.findForTask(taskId).stream().map(CommentResponse::of).toList();
    }

    public CommentResponse addComment(User user, Long taskId, String body) {
        Task task = support.editableTask(taskId, user);
        Comment comment = comments.save(new Comment(task, user, body.trim()));

        Map<Long, User> mentioned = new HashMap<>();
        Set<String> names = MentionParser.usernames(comment.getBody());
        for (User member : task.getProject().getMembers()) {
            if (names.contains(member.getUsername().toLowerCase(Locale.ROOT))) {
                mentioned.put(member.getId(), member);
            }
        }
        mentioned.values().forEach(member -> notifications.notify(member, user, task, "mentioned you in"));
        for (User participant : support.participants(task)) {
            if (!mentioned.containsKey(participant.getId())) {
                notifications.notify(participant, user, task, "commented on");
            }
        }
        // Commenting on a task means you probably want to hear about replies.
        if (!task.isReporter(user) && !task.isAssignee(user) && !task.isWatchedBy(user)) {
            task.getWatchers().add(user);
        }
        live.taskChanged(task);
        return CommentResponse.of(comment);
    }

    // ---------------------------------------------------------------- checklist

    @Transactional(readOnly = true)
    public List<ChecklistItemResponse> checklist(User user, Long taskId) {
        support.memberTask(taskId, user);
        return checklist.findByTaskIdOrderByPositionAscIdAsc(taskId).stream().map(ChecklistItemResponse::of).toList();
    }

    public ChecklistItemResponse addChecklistItem(User user, Long taskId, String text) {
        Task task = support.editableTask(taskId, user);
        ChecklistItem item = checklist.save(new ChecklistItem(task, text.trim(), checklist.maxPosition(taskId) + 1));
        live.taskChanged(task);
        return ChecklistItemResponse.of(item);
    }

    public ChecklistItemResponse updateChecklistItem(User user, Long taskId, Long itemId, ChecklistUpdateRequest request) {
        Task task = support.editableTask(taskId, user);
        ChecklistItem item = checklist.findByIdAndTaskId(itemId, taskId)
                .orElseThrow(() -> ApiException.notFound("Checklist item not found."));
        if (request.text() != null) {
            item.setText(request.text().trim());
        }
        if (request.done() != null) {
            item.setDone(request.done());
        }
        live.taskChanged(task);
        return ChecklistItemResponse.of(item);
    }

    public void deleteChecklistItem(User user, Long taskId, Long itemId) {
        Task task = support.editableTask(taskId, user);
        ChecklistItem item = checklist.findByIdAndTaskId(itemId, taskId)
                .orElseThrow(() -> ApiException.notFound("Checklist item not found."));
        checklist.delete(item);
        live.taskChanged(task);
    }

    // ---------------------------------------------------------------- activity

    @Transactional(readOnly = true)
    public List<ActivityResponse> activity(User user, Long taskId) {
        support.memberTask(taskId, user);
        return activity.findForTask(taskId).stream().map(ActivityResponse::of).toList();
    }

    // ---------------------------------------------------------------- links

    @Transactional(readOnly = true)
    public List<LinkResponse> links(User user, Long taskId) {
        support.memberTask(taskId, user);
        return links.findForTask(taskId).stream()
                .filter(link -> other(link, taskId).getProject().hasMember(user))
                .map(link -> view(link, taskId))
                .sorted(Comparator.comparing(LinkResponse::label).thenComparing(l -> l.task().key()))
                .toList();
    }

    public LinkResponse addLink(User user, Long taskId, LinkRequest request) {
        Task source = support.editableTask(taskId, user);
        Task target;
        if (request.targetId() != null) {
            target = support.memberTask(request.targetId(), user);
        } else if (request.targetKey() != null && !request.targetKey().isBlank()) {
            target = taskService.taskByKey(user, request.targetKey().trim().toUpperCase(Locale.ROOT));
        } else {
            throw ApiException.badRequest("Choose a task to link to.");
        }
        if (target.getId().equals(source.getId())) {
            throw ApiException.badRequest("A task cannot be linked to itself.");
        }
        if (links.existsBySourceIdAndTargetIdAndType(source.getId(), target.getId(), request.type())
                || (request.type() == LinkType.RELATES
                && links.existsBySourceIdAndTargetIdAndType(target.getId(), source.getId(), LinkType.RELATES))) {
            throw ApiException.conflict("These tasks are already linked that way.");
        }
        TaskLink link = links.save(new TaskLink(source, target, request.type(), user));
        support.record(source, user, request.type().outward() + " " + target.getKey());
        support.record(target, user, request.type().inward() + " " + source.getKey());
        live.taskChanged(source);
        live.taskChanged(target);
        return view(link, source.getId());
    }

    public void deleteLink(User user, Long taskId, Long linkId) {
        Task task = support.editableTask(taskId, user);
        TaskLink link = links.findById(linkId)
                .filter(l -> l.getSource().getId().equals(taskId) || l.getTarget().getId().equals(taskId))
                .orElseThrow(() -> ApiException.notFound("Link not found."));
        Task other = other(link, taskId);
        links.delete(link);
        support.record(task, user, "removed the link to " + other.getKey());
        live.taskChanged(task);
        live.taskChanged(other);
    }

    private static Task other(TaskLink link, Long taskId) {
        return link.getSource().getId().equals(taskId) ? link.getTarget() : link.getSource();
    }

    private static LinkResponse view(TaskLink link, Long taskId) {
        boolean outward = link.getSource().getId().equals(taskId);
        return new LinkResponse(link.getId(), link.getType(),
                outward ? link.getType().outward() : link.getType().inward(),
                TaskRef.of(outward ? link.getTarget() : link.getSource()));
    }

    // ---------------------------------------------------------------- time tracking

    @Transactional(readOnly = true)
    public List<TimeEntryResponse> time(User user, Long taskId) {
        support.memberTask(taskId, user);
        return time.findForTask(taskId).stream().map(TimeEntryResponse::of).toList();
    }

    public TimeEntryResponse logTime(User user, Long taskId, TimeRequest request) {
        Task task = support.editableTask(taskId, user);
        LocalDate date = request.date() == null ? LocalDate.now() : request.date();
        if (date.isAfter(LocalDate.now().plusDays(1))) {
            throw ApiException.badRequest("Time cannot be logged in the future.");
        }
        TimeEntry entry = time.save(new TimeEntry(task, user, request.minutes(), date,
                request.note() == null ? "" : request.note().trim()));
        support.record(task, user, "logged " + formatMinutes(request.minutes()));
        live.taskChanged(task);
        return TimeEntryResponse.of(entry);
    }

    public void deleteTime(User user, Long taskId, Long entryId) {
        Task task = support.memberTask(taskId, user);
        TimeEntry entry = time.findById(entryId)
                .filter(e -> e.getTask().getId().equals(taskId))
                .orElseThrow(() -> ApiException.notFound("Time entry not found."));
        if (!entry.getUser().getId().equals(user.getId()) && !task.getProject().isOwner(user)) {
            throw ApiException.forbidden("You can only delete your own time entries.");
        }
        time.delete(entry);
        live.taskChanged(task);
    }

    public static String formatMinutes(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + "m";
        }
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    }

    // ---------------------------------------------------------------- watching

    @Transactional(readOnly = true)
    public WatchersResponse watchers(User user, Long taskId) {
        Task task = support.memberTask(taskId, user);
        return new WatchersResponse(task.isWatchedBy(user),
                task.getWatchers().stream().map(UserSummary::of).toList());
    }

    /** Viewers may watch too: it only affects their own notifications. */
    public WatchersResponse watch(User user, Long taskId, boolean watching) {
        Task task = support.memberTask(taskId, user);
        if (watching && !task.isWatchedBy(user)) {
            task.getWatchers().add(user);
        } else if (!watching) {
            task.getWatchers().removeIf(watcher -> watcher.getId().equals(user.getId()));
        }
        return new WatchersResponse(watching, task.getWatchers().stream().map(UserSummary::of).toList());
    }

    // ---------------------------------------------------------------- development (GitHub)

    @Transactional(readOnly = true)
    public List<DevLinkResponse> devLinks(User user, Long taskId) {
        support.memberTask(taskId, user);
        return devLinks.findByTaskIdOrderByUpdatedAtDesc(taskId).stream().map(DevLinkResponse::of).toList();
    }
}
