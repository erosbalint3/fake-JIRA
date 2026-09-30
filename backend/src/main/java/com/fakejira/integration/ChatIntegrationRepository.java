package com.fakejira.integration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChatIntegrationRepository extends JpaRepository<ChatIntegration, Long> {

    Optional<ChatIntegration> findByProjectId(Long projectId);

    @Modifying
    @Query("delete from ChatIntegration c where c.projectId = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
