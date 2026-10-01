package com.fakejira.report;

import com.fakejira.task.TaskPriority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, Long> {

    List<SlaPolicy> findByProjectId(Long projectId);

    Optional<SlaPolicy> findByProjectIdAndPriority(Long projectId, TaskPriority priority);

    @Modifying
    @Query("delete from SlaPolicy p where p.projectId = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);

    /** First comment by someone other than the reporter, per task of the project. */
    @Query("""
            select c.task.id, min(c.createdAt) from Comment c
            where c.task.project.id = :projectId and c.author.id <> c.task.reporter.id
            group by c.task.id""")
    List<Object[]> firstResponses(@Param("projectId") Long projectId);

    @Query("""
            select min(c.createdAt) from Comment c
            where c.task.id = :taskId and c.author.id <> c.task.reporter.id""")
    Instant firstResponse(@Param("taskId") Long taskId);
}
