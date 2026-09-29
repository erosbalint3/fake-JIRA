package com.fakejira.integration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DevLinkRepository extends JpaRepository<DevLink, Long> {

    Optional<DevLink> findByTaskIdAndKindAndExternalId(Long taskId, DevLink.Kind kind, String externalId);

    List<DevLink> findByTaskIdOrderByUpdatedAtDesc(Long taskId);

    @Modifying
    @Query("delete from DevLink d where d.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
