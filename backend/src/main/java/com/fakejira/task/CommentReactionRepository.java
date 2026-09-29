package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommentReactionRepository extends JpaRepository<CommentReaction, Long> {

    @Query("select r from CommentReaction r join fetch r.user where r.comment.task.id = :taskId order by r.id")
    List<CommentReaction> findForTask(@Param("taskId") Long taskId);

    @Query("select r from CommentReaction r join fetch r.user where r.comment.id = :commentId order by r.id")
    List<CommentReaction> findForComment(@Param("commentId") Long commentId);

    Optional<CommentReaction> findByCommentIdAndUserIdAndEmoji(Long commentId, Long userId, String emoji);

    @Modifying
    @Query("delete from CommentReaction r where r.comment.id in (select c.id from Comment c where c.task.id = :taskId)")
    void deleteForTask(@Param("taskId") Long taskId);

    @Modifying
    @Query("delete from CommentReaction r where r.comment.id = :commentId")
    void deleteForComment(@Param("commentId") Long commentId);
}
