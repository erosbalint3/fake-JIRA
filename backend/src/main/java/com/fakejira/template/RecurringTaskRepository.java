package com.fakejira.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface RecurringTaskRepository extends JpaRepository<RecurringTask, Long> {

    List<RecurringTask> findByProjectIdOrderByIdAsc(Long projectId);

    @Query("select r from RecurringTask r where r.active = true and r.nextRun <= :today")
    List<RecurringTask> findDue(LocalDate today);

    @Query("select r from RecurringTask r where r.assignee.id = :userId or r.createdBy.id = :userId")
    List<RecurringTask> findInvolving(Long userId);
}
