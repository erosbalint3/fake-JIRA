package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StatusChangeRepository extends JpaRepository<StatusChange, Long> {

    List<StatusChange> findByProjectIdOrderByChangedAtAsc(Long projectId);

    @Modifying
    @Query("delete from StatusChange s where s.taskId = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);

    /** Moves history along when a task moves to another project. */
    @Modifying
    @Query("update StatusChange s set s.projectId = :projectId where s.taskId = :taskId")
    void moveTask(@Param("taskId") Long taskId, @Param("projectId") Long projectId);
}
