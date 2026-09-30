package com.fakejira.integration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BuildStatusRepository extends JpaRepository<BuildStatus, Long> {

    List<BuildStatus> findByTaskIdOrderByUpdatedAtDesc(Long taskId);

    Optional<BuildStatus> findByTaskIdAndSourceAndName(Long taskId, String source, String name);

    @Query("select b from BuildStatus b join fetch b.task t where t.project.id = :projectId and t.archivedAt is null order by b.updatedAt desc")
    List<BuildStatus> forProject(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from BuildStatus b where b.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
