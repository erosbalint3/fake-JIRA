package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, Long> {

    @Query("select e from TimeEntry e join fetch e.user where e.task.id = :taskId order by e.workDate desc, e.id desc")
    List<TimeEntry> findForTask(@Param("taskId") Long taskId);

    /** Rows of [taskId, minutes]. */
    @Query("select e.task.id, sum(e.minutes) from TimeEntry e where e.task.id in :taskIds group by e.task.id")
    List<Object[]> totalsFor(@Param("taskIds") Collection<Long> taskIds);

    @Query("""
            select e from TimeEntry e join fetch e.user join fetch e.task t
            where t.project.id = :projectId and e.workDate between :from and :to
            order by e.workDate
            """)
    List<TimeEntry> findInProject(@Param("projectId") Long projectId, @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    @Modifying
    @Query("delete from TimeEntry e where e.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
