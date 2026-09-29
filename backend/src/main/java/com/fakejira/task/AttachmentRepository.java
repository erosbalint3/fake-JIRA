package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    @Query("select a from Attachment a join fetch a.uploader where a.task.id = :taskId order by a.createdAt asc")
    List<Attachment> findForTask(@Param("taskId") Long taskId);

    @Query("select coalesce(sum(a.size), 0) from Attachment a where a.task.project.id = :projectId")
    long bytesInProject(@Param("projectId") Long projectId);

    @Query("select count(a) from Attachment a where a.task.project.id = :projectId")
    long countInProject(@Param("projectId") Long projectId);

    @Query("select coalesce(sum(a.size), 0) from Attachment a")
    long totalBytes();

    /** [project key, project name, bytes, files] per project, largest first. */
    @Query("select a.task.project.key, a.task.project.name, sum(a.size), count(a) from Attachment a "
            + "group by a.task.project.key, a.task.project.name order by sum(a.size) desc")
    List<Object[]> usageByProject();
}
