package com.fakejira.task;

import com.fakejira.common.CurrentUser;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.task.TaskDtos.TaskRef;
import com.fakejira.user.UserSummary;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Every picture attached in a project, in one place (screenshots, mockups, photos). */
@RestController
public class GalleryController {

    static final int MAX_IMAGES = 500;

    public record GalleryImage(Long id, String filename, long size, Instant createdAt, TaskRef task, UserSummary uploader) {
    }

    private final AttachmentRepository attachments;
    private final ProjectAccess access;
    private final CurrentUser currentUser;

    public GalleryController(AttachmentRepository attachments, ProjectAccess access, CurrentUser currentUser) {
        this.attachments = attachments;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/projects/{key}/gallery")
    @Transactional(readOnly = true)
    public List<GalleryImage> gallery(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        Project project = access.memberProject(key, currentUser.from(jwt));
        return attachments.imagesInProject(project.getId(), PageRequest.of(0, MAX_IMAGES)).stream()
                .map(a -> new GalleryImage(a.getId(), a.getFilename(), a.getSize(), a.getCreatedAt(), TaskRef.of(a.getTask()),
                        UserSummary.of(a.getUploader())))
                .toList();
    }
}
