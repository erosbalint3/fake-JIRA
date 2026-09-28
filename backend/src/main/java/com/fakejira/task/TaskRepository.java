package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    @Query("""
            select t from Task t
            join fetch t.reporter r
            left join fetch t.assignee a
            where (:unassignedOnly = false or a is null)
              and (:assigneeId is null or a.id = :assigneeId)
              and (:reporterId is null or r.id = :reporterId)
              and (:priority is null or t.priority = :priority)
              and (:status is null or t.status = :status)
              and (:pattern is null or lower(t.title) like :pattern or lower(t.description) like :pattern)
            order by t.updatedAt desc
            """)
    List<Task> search(@Param("unassignedOnly") boolean unassignedOnly,
                      @Param("assigneeId") Long assigneeId,
                      @Param("reporterId") Long reporterId,
                      @Param("priority") TaskPriority priority,
                      @Param("status") TaskStatus status,
                      @Param("pattern") String pattern);

    long countByAssigneeId(Long assigneeId);

    long countByAssigneeIdAndStatus(Long assigneeId, TaskStatus status);

    long countByReporterId(Long reporterId);
}
