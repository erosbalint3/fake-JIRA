package com.fakejira.board;

import com.fakejira.common.ApiException;
import com.fakejira.events.LiveEvents;
import com.fakejira.project.Project;
import com.fakejira.project.ProjectAccess;
import com.fakejira.task.Task;
import com.fakejira.task.TaskRepository;
import com.fakejira.task.TaskStatus;
import com.fakejira.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Per-project board columns. Every status must keep at least one column so every task has a home. */
@Service
@Transactional
public class BoardService {

    public record ColumnInput(String name, TaskStatus status, Integer wipLimit) {
    }

    public record ColumnResponse(Long id, String name, TaskStatus status, int position, Integer wipLimit) {
        static ColumnResponse of(BoardColumn column) {
            return new ColumnResponse(column.getId(), column.getName(), column.getStatus(), column.getPosition(),
                    column.getWipLimit());
        }
    }

    private final BoardColumnRepository columns;
    private final TaskRepository tasks;
    private final ProjectAccess access;
    private final LiveEvents live;

    public BoardService(BoardColumnRepository columns, TaskRepository tasks, ProjectAccess access, LiveEvents live) {
        this.columns = columns;
        this.tasks = tasks;
        this.access = access;
        this.live = live;
    }

    public List<ColumnResponse> list(User user, String projectKey) {
        Project project = access.memberProject(projectKey, user);
        return ensureDefaults(project).stream().map(ColumnResponse::of).toList();
    }

    public List<ColumnResponse> add(User user, String projectKey, ColumnInput input) {
        Project project = access.editorProject(projectKey, user);
        List<BoardColumn> existing = ensureDefaults(project);
        validate(input);
        if (existing.size() >= 12) {
            throw ApiException.badRequest("A board can have at most 12 columns.");
        }
        // New columns go after the last column of the same status, keeping the board in workflow order.
        int insertAt = existing.size();
        for (int i = 0; i < existing.size(); i++) {
            if (existing.get(i).getStatus().ordinal() <= input.status().ordinal()) {
                insertAt = i + 1;
            }
        }
        BoardColumn column = columns.save(new BoardColumn(project, input.name().trim(), input.status(), insertAt,
                normalizeLimit(input.wipLimit())));
        existing.add(insertAt, column);
        renumber(existing);
        live.projectChanged(project);
        return existing.stream().map(ColumnResponse::of).toList();
    }

    public List<ColumnResponse> update(User user, Long columnId, ColumnInput input) {
        BoardColumn column = editable(columnId, user);
        validate(input);
        List<BoardColumn> all = ensureDefaults(column.getProject());
        if (column.getStatus() != input.status()) {
            requireOtherColumn(all, column);
            // Tasks pinned to this column follow its new status.
            for (Task task : tasks.findByBoardColumnId(column.getId())) {
                task.setBoardColumn(null);
            }
        }
        column.setName(input.name().trim());
        column.setStatus(input.status());
        column.setWipLimit(normalizeLimit(input.wipLimit()));
        live.projectChanged(column.getProject());
        return all.stream().map(ColumnResponse::of).toList();
    }

    public List<ColumnResponse> move(User user, Long columnId, int direction) {
        BoardColumn column = editable(columnId, user);
        List<BoardColumn> all = ensureDefaults(column.getProject());
        int index = indexOf(all, column);
        int target = index + (direction < 0 ? -1 : 1);
        if (target >= 0 && target < all.size()) {
            all.set(index, all.get(target));
            all.set(target, column);
            renumber(all);
            live.projectChanged(column.getProject());
        }
        return all.stream().map(ColumnResponse::of).toList();
    }

    public List<ColumnResponse> delete(User user, Long columnId) {
        BoardColumn column = editable(columnId, user);
        List<BoardColumn> all = ensureDefaults(column.getProject());
        requireOtherColumn(all, column);
        for (Task task : tasks.findByBoardColumnId(column.getId())) {
            task.setBoardColumn(null);
        }
        all.remove(indexOf(all, column));
        columns.delete(column);
        renumber(all);
        live.projectChanged(column.getProject());
        return all.stream().map(ColumnResponse::of).toList();
    }

    /** Creates the standard four columns the first time a project's board is configured or read. */
    public List<BoardColumn> ensureDefaults(Project project) {
        List<BoardColumn> list = new java.util.ArrayList<>(columns.findByProjectIdOrderByPositionAscIdAsc(project.getId()));
        Set<TaskStatus> covered = list.stream().map(BoardColumn::getStatus).collect(Collectors.toCollection(
                () -> EnumSet.noneOf(TaskStatus.class)));
        if (list.isEmpty() || covered.size() < TaskStatus.values().length) {
            for (TaskStatus status : TaskStatus.values()) {
                if (!covered.contains(status)) {
                    list.add(columns.save(new BoardColumn(project, status.label(), status, list.size(), null)));
                }
            }
            list.sort((a, b) -> a.getStatus() != b.getStatus()
                    ? Integer.compare(a.getStatus().ordinal(), b.getStatus().ordinal())
                    : Integer.compare(a.getPosition(), b.getPosition()));
            renumber(list);
        }
        return list;
    }

    private BoardColumn editable(Long id, User user) {
        BoardColumn column = columns.findById(id).orElseThrow(() -> ApiException.notFound("Column not found."));
        access.requireEditor(column.getProject(), user);
        return column;
    }

    private static void requireOtherColumn(List<BoardColumn> all, BoardColumn column) {
        boolean another = all.stream()
                .anyMatch(c -> !c.getId().equals(column.getId()) && c.getStatus() == column.getStatus());
        if (!another) {
            throw ApiException.badRequest("Every status needs at least one column; add another \""
                    + column.getStatus().label() + "\" column first.");
        }
    }

    private static int indexOf(List<BoardColumn> all, BoardColumn column) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(column.getId())) {
                return i;
            }
        }
        throw ApiException.notFound("Column not found.");
    }

    private static void renumber(List<BoardColumn> list) {
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setPosition(i);
        }
    }

    private static void validate(ColumnInput input) {
        if (input.name() == null || input.name().isBlank() || input.name().trim().length() > 40) {
            throw ApiException.badRequest("Column names must be 1-40 characters.");
        }
        if (input.status() == null) {
            throw ApiException.badRequest("Choose which status the column represents.");
        }
    }

    private static Integer normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return null;
        }
        return Math.min(limit, 99);
    }
}
