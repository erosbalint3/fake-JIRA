package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TaskLinkRepository extends JpaRepository<TaskLink, Long> {

    @Query("select l from TaskLink l join fetch l.source join fetch l.target where l.source.id = :taskId or l.target.id = :taskId")
    List<TaskLink> findForTask(@Param("taskId") Long taskId);

    @Query("select l from TaskLink l join fetch l.source s join fetch l.target where s.project.id = :projectId "
            + "and l.target.project.id = :projectId and l.type = com.fakejira.task.LinkType.BLOCKS")
    List<TaskLink> blocksInProject(@Param("projectId") Long projectId);

    boolean existsBySourceIdAndTargetIdAndType(Long sourceId, Long targetId, LinkType type);

    /** Ids of tasks (among the given) blocked by a task that is not done yet. */
    @Query("""
            select distinct l.target.id from TaskLink l
            where l.type = com.fakejira.task.LinkType.BLOCKS and l.target.id in :taskIds
              and l.source.status <> com.fakejira.task.TaskStatus.DONE
            """)
    List<Long> blockedAmong(@Param("taskIds") Collection<Long> taskIds);

    @Modifying
    @Query("delete from TaskLink l where l.source.id = :taskId or l.target.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
