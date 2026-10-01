package com.fakejira.component;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectComponentRepository extends JpaRepository<ProjectComponent, Long> {

    List<ProjectComponent> findByProjectIdOrderByNameAsc(Long projectId);

    @Query(value = "select count(*) from task_components where component_id = :id", nativeQuery = true)
    long countTasks(@Param("id") Long id);

    @Modifying
    @Query(value = "delete from task_components where component_id = :id", nativeQuery = true)
    void detachTasks(@Param("id") Long id);

    @Modifying
    @Query(value = "update project_components set lead_id = null where lead_id = :userId and project_id = :projectId", nativeQuery = true)
    void clearLead(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
