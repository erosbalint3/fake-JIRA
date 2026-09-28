package com.fakejira.task;

import com.fakejira.project.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    List<Task> findBySprintId(Long sprintId);

    List<Task> findByProjectId(Long projectId);

    long countByAssigneeId(Long assigneeId);

    long countByAssigneeIdAndStatus(Long assigneeId, TaskStatus status);

    long countByReporterId(Long reporterId);

    @Query("select distinct l from Task t join t.labels l where t.project.id = :projectId order by l")
    List<String> labelsInProject(@Param("projectId") Long projectId);

    @Modifying
    @Query("update Task t set t.assignee = null where t.project.id = :projectId and t.assignee.id = :userId")
    int unassignInProject(@Param("projectId") Long projectId, @Param("userId") Long userId);

    // --- legacy upgrade helpers (bulk updates skip @PreUpdate so updatedAt is preserved) ---

    long countByProjectIsNull();

    @Modifying
    @Query("update Task t set t.project = :project, t.number = cast(t.id as integer) where t.project is null")
    int adoptOrphans(@Param("project") Project project);

    @Modifying
    @Query("update Task t set t.completedAt = t.updatedAt where t.status = com.fakejira.task.TaskStatus.DONE and t.completedAt is null")
    int backfillCompletedAt();

    @Query("select coalesce(max(t.number), 0) from Task t where t.project.id = :projectId")
    int maxNumber(@Param("projectId") Long projectId);
}
