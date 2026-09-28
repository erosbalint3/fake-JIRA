package com.fakejira.task;

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

    public TaskCleanup(TaskRepository tasks, CommentRepository comments, ChecklistItemRepository checklist,
                       TaskActivityRepository activity, AttachmentRepository attachments, AttachmentStorage storage) {
        this.tasks = tasks;
        this.comments = comments;
        this.checklist = checklist;
        this.activity = activity;
        this.attachments = attachments;
        this.storage = storage;
    }

    public void delete(Task task) {
        List<Attachment> files = attachments.findForTask(task.getId());
        List<String> storageNames = files.stream().map(Attachment::getStorageName).toList();
        attachments.deleteAll(files);
        comments.deleteForTask(task.getId());
        checklist.deleteForTask(task.getId());
        activity.deleteForTask(task.getId());
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
