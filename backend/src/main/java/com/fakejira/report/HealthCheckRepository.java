package com.fakejira.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HealthCheckRepository extends JpaRepository<HealthCheck, Long> {

    List<HealthCheck> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    @Modifying
    @Query("delete from HealthCheck h where h.project.id = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
