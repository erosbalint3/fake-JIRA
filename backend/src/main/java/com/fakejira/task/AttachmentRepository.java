package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    @Query("select a from Attachment a join fetch a.uploader where a.task.id = :taskId order by a.createdAt asc")
    List<Attachment> findForTask(@Param("taskId") Long taskId);
}
