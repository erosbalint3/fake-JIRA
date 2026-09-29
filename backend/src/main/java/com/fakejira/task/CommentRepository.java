package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @Query("select c from Comment c join fetch c.author where c.task.id = :taskId order by c.createdAt asc")
    List<Comment> findForTask(@Param("taskId") Long taskId);

    @Modifying
    @Query("delete from Comment c where c.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);

    /** Run before {@link #deleteForTask} so replies do not block deleting their parents. */
    @Modifying
    @Query("update Comment c set c.parent = null where c.task.id = :taskId")
    void detachReplies(@Param("taskId") Long taskId);

    List<Comment> findByParentId(Long parentId);
}
