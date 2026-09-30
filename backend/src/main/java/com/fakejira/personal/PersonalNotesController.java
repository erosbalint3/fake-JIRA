package com.fakejira.personal;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.task.Task;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Private notes and a private checklist on a task; only their owner ever sees them. */
@RestController
@Transactional
public class PersonalNotesController {

    static final int MAX_ITEMS = 100;

    private final PersonalNoteRepository notes;
    private final PrivateItemRepository items;
    private final TaskSupport taskSupport;
    private final CurrentUser currentUser;

    public PersonalNotesController(PersonalNoteRepository notes, PrivateItemRepository items, TaskSupport taskSupport,
                                   CurrentUser currentUser) {
        this.notes = notes;
        this.items = items;
        this.taskSupport = taskSupport;
        this.currentUser = currentUser;
    }

    public record ItemResponse(Long id, String text, boolean done) {
        static ItemResponse of(PrivateItem item) {
            return new ItemResponse(item.getId(), item.getText(), item.isDone());
        }
    }

    public record PersonalResponse(String note, Instant updatedAt, List<ItemResponse> items) {
    }

    public record NoteRequest(@NotNull(message = "Enter a note")
                              @Size(max = PersonalNote.MAX_BODY, message = "Notes must be at most 10000 characters")
                              String body) {
    }

    public record ItemRequest(@NotBlank(message = "Enter the item")
                              @Size(max = 200, message = "Items must be at most 200 characters") String text) {
    }

    public record ItemPatch(@Size(max = 200, message = "Items must be at most 200 characters") String text, Boolean done) {
    }

    @GetMapping("/api/tasks/{id}/personal")
    @Transactional(readOnly = true)
    public PersonalResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        taskSupport.memberTask(id, user);
        return response(user, id);
    }

    @PutMapping("/api/tasks/{id}/personal/note")
    public PersonalResponse saveNote(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                     @Valid @RequestBody NoteRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.memberTask(id, user);
        PersonalNote note = notes.findByUserIdAndTaskId(user.getId(), id).orElse(null);
        if (request.body().isBlank()) {
            if (note != null) {
                notes.delete(note);
            }
        } else {
            if (note == null) {
                note = new PersonalNote(user, task);
            }
            note.setBody(request.body());
            notes.save(note);
        }
        notes.flush();
        return response(user, id);
    }

    @PostMapping("/api/tasks/{id}/personal/items")
    @ResponseStatus(HttpStatus.CREATED)
    public PersonalResponse addItem(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                    @Valid @RequestBody ItemRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.memberTask(id, user);
        if (items.findByUserIdAndTaskIdOrderByPositionAscIdAsc(user.getId(), id).size() >= MAX_ITEMS) {
            throw ApiException.badRequest("A private checklist holds at most " + MAX_ITEMS + " items.");
        }
        items.save(new PrivateItem(user, task, request.text().trim(), items.maxPosition(user.getId(), id) + 1));
        return response(user, id);
    }

    @PatchMapping("/api/personal-items/{itemId}")
    public PersonalResponse updateItem(@AuthenticationPrincipal Jwt jwt, @PathVariable Long itemId,
                                       @Valid @RequestBody ItemPatch request) {
        User user = currentUser.from(jwt);
        PrivateItem item = own(user, itemId);
        if (request.text() != null) {
            if (request.text().isBlank()) {
                throw ApiException.field("text", "Enter the item");
            }
            item.setText(request.text().trim());
        }
        if (request.done() != null) {
            item.setDone(request.done());
        }
        return response(user, item.getTask().getId());
    }

    @DeleteMapping("/api/personal-items/{itemId}")
    public PersonalResponse deleteItem(@AuthenticationPrincipal Jwt jwt, @PathVariable Long itemId) {
        User user = currentUser.from(jwt);
        PrivateItem item = own(user, itemId);
        Long taskId = item.getTask().getId();
        items.delete(item);
        items.flush();
        return response(user, taskId);
    }

    private PrivateItem own(User user, Long itemId) {
        PrivateItem item = items.findByIdAndUserId(itemId, user.getId())
                .orElseThrow(() -> ApiException.notFound("Checklist item not found."));
        taskSupport.memberTask(item.getTask().getId(), user);
        return item;
    }

    private PersonalResponse response(User user, Long taskId) {
        PersonalNote note = notes.findByUserIdAndTaskId(user.getId(), taskId).orElse(null);
        return new PersonalResponse(note == null ? "" : note.getBody(), note == null ? null : note.getUpdatedAt(),
                items.findByUserIdAndTaskIdOrderByPositionAscIdAsc(user.getId(), taskId).stream()
                        .map(ItemResponse::of).toList());
    }
}
