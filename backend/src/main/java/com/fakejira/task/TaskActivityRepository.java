package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskActivityRepository extends JpaRepository<TaskActivity, Long> {

    @Query("select a from TaskActivity a join fetch a.actor where a.task.id = :taskId order by a.createdAt desc, a.id desc")
    List<TaskActivity> findForTask(@Param("taskId") Long taskId);

    @Modifying
    @Query("delete from TaskActivity a where a.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
