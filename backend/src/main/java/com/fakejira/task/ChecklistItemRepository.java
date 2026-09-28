package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Long> {

    List<ChecklistItem> findByTaskIdOrderByPositionAscIdAsc(Long taskId);

    Optional<ChecklistItem> findByIdAndTaskId(Long id, Long taskId);

    @Query("select coalesce(max(c.position), 0) from ChecklistItem c where c.task.id = :taskId")
    int maxPosition(@Param("taskId") Long taskId);

    /** Rows of [taskId, total, done] for the given tasks. */
    @Query("""
            select c.task.id, count(c), sum(case when c.done = true then 1 else 0 end)
            from ChecklistItem c where c.task.id in :taskIds group by c.task.id
            """)
    List<Object[]> progressFor(@Param("taskIds") Collection<Long> taskIds);

    @Modifying
    @Query("delete from ChecklistItem c where c.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
