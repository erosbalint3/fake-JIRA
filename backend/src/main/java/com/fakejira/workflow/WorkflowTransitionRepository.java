package com.fakejira.workflow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, Long> {

    List<WorkflowTransition> findByProjectIdOrderByIdAsc(Long projectId);

    @Modifying
    @Query("delete from WorkflowTransition t where t.project.id = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from WorkflowTransition t where t.from.id = :columnId or t.to.id = :columnId")
    void deleteForColumn(@Param("columnId") Long columnId);
}
