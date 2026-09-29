package com.fakejira.task;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.task.TaskDtos.AttachmentResponse;
import com.fakejira.user.User;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public class AttachmentController {

    private final TaskSupport support;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final CurrentUser currentUser;
    private final LiveEvents live;

    public AttachmentController(TaskSupport support, AttachmentRepository attachments,
                                AttachmentStorage storage, CurrentUser currentUser, LiveEvents live) {
        this.support = support;
        this.attachments = attachments;
        this.storage = storage;
        this.currentUser = currentUser;
        this.live = live;
    }

    @GetMapping("/api/tasks/{taskId}/attachments")
    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long taskId) {
        support.memberTask(taskId, currentUser.from(jwt));
        return attachments.findForTask(taskId).stream().map(AttachmentResponse::of).toList();
    }

    @PostMapping(path = "/api/tasks/{taskId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AttachmentResponse upload(@AuthenticationPrincipal Jwt jwt, @PathVariable Long taskId,
                                     @RequestParam("file") MultipartFile file) {
        User user = currentUser.from(jwt);
        Task task = support.editableTask(taskId, user);
        if (file.isEmpty()) {
            throw ApiException.badRequest("The file is empty.");
        }
        String filename = cleanFilename(file.getOriginalFilename());
        String contentType = file.getContentType() == null || file.getContentType().length() > 150
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : file.getContentType();
        String storageName = storage.store(file);
        Attachment attachment = attachments.save(
                new Attachment(task, user, filename, contentType, file.getSize(), storageName));
        support.record(task, user, "attached " + filename);
        live.taskChanged(task);
        return AttachmentResponse.of(attachment);
    }

    /**
     * Always served as a download (never rendered inline) so uploaded HTML/SVG cannot run
     * scripts on this origin; the UI previews images from the downloaded blob.
     */
    @GetMapping("/api/attachments/{id}/content")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Attachment attachment = memberAttachment(id, currentUser.from(jwt));
        return ResponseEntity.ok()
                .contentType(safeMediaType(attachment.getContentType()))
                .contentLength(attachment.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.getFilename(), StandardCharsets.UTF_8).build().toString())
                .body(storage.load(attachment.getStorageName()));
    }

    @DeleteMapping("/api/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Attachment attachment = memberAttachment(id, user);
        Task task = attachment.getTask();
        if (!task.getProject().canEdit(user)) {
            throw ApiException.forbidden("You have read-only access to " + task.getProject().getKey() + ".");
        }
        if (!attachment.getUploader().getId().equals(user.getId()) && !task.getProject().isOwner(user)) {
            throw ApiException.forbidden("Only the uploader or the project owner can delete this file.");
        }
        attachments.delete(attachment);
        support.record(task, user, "removed attachment " + attachment.getFilename());
        TaskCleanup.afterCommit(() -> storage.delete(attachment.getStorageName()));
        live.taskChanged(task);
    }

    private Attachment memberAttachment(Long id, User user) {
        Attachment attachment = attachments.findById(id)
                .orElseThrow(() -> ApiException.notFound("File not found."));
        if (!attachment.getTask().getProject().hasMember(user)) {
            throw ApiException.notFound("File not found.");
        }
        return attachment;
    }

    private static MediaType safeMediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    static String cleanFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty()) {
            name = "file";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
