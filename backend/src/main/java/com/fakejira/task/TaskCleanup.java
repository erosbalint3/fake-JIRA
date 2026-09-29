package com.fakejira.task;

import com.fakejira.integration.DevLinkRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Deletes a task together with everything hanging off it (files are removed after commit). */
@Component
public class TaskCleanup {

    private final TaskRepository tasks;
    private final CommentRepository comments;
    private final ChecklistItemRepository checklist;
    private final TaskActivityRepository activity;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final TaskLinkRepository links;
    private final TimeEntryRepository time;
    private final DevLinkRepository devLinks;
    private final CommentReactionRepository reactions;
    private final org.springframework.context.ApplicationEventPublisher events;

    public TaskCleanup(TaskRepository tasks, CommentRepository comments, ChecklistItemRepository checklist,
                       TaskActivityRepository activity, AttachmentRepository attachments, AttachmentStorage storage,
                       TaskLinkRepository links, TimeEntryRepository time, DevLinkRepository devLinks,
                       CommentReactionRepository reactions, org.springframework.context.ApplicationEventPublisher events) {
        this.reactions = reactions;
        this.events = events;
        this.tasks = tasks;
        this.comments = comments;
        this.checklist = checklist;
        this.activity = activity;
        this.attachments = attachments;
        this.storage = storage;
        this.links = links;
        this.time = time;
        this.devLinks = devLinks;
    }

    /** Deletes the task, its subtasks and everything attached to them. */
    public void delete(Task task) {
        for (Task subtask : tasks.findByParentIdOrderByIdAsc(task.getId())) {
            delete(subtask);
        }
        List<Attachment> files = attachments.findForTask(task.getId());
        List<String> storageNames = files.stream().map(Attachment::getStorageName).toList();
        attachments.deleteAll(files);
        events.publishEvent(new TaskDeleting(task.getId()));
        reactions.deleteForTask(task.getId());
        comments.detachReplies(task.getId());
        comments.deleteForTask(task.getId());
        checklist.deleteForTask(task.getId());
        activity.deleteForTask(task.getId());
        links.deleteForTask(task.getId());
        time.deleteForTask(task.getId());
        devLinks.deleteForTask(task.getId());
        task.getWatchers().clear();
        tasks.delete(task);
        afterCommit(() -> storageNames.forEach(storage::delete));
    }

    static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
