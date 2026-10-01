package com.fakejira.decision;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DecisionRepository extends JpaRepository<Decision, Long> {

    List<Decision> findByProjectIdOrderByDecidedAtDesc(Long projectId);

    List<Decision> findByTaskIdOrderByDecidedAtDesc(Long taskId);

    @Modifying
    @Query("update Decision d set d.task = null where d.task.id = :taskId")
    void detachTask(@Param("taskId") Long taskId);

    @Modifying
    @Query("delete from Decision d where d.project.id = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
