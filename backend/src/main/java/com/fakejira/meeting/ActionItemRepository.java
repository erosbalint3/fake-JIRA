package com.fakejira.meeting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ActionItemRepository extends JpaRepository<ActionItem, Long> {

    List<ActionItem> findByNoteIdOrderByIdAsc(Long noteId);

    @Modifying
    @Query("update ActionItem a set a.task = null where a.task.id = :taskId")
    void detachTask(@Param("taskId") Long taskId);

    @Modifying
    @Query("delete from ActionItem a where a.note.id = :noteId")
    void deleteForNote(@Param("noteId") Long noteId);
}
