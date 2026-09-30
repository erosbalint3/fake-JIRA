package com.fakejira.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CalendarEventLinkRepository extends JpaRepository<CalendarEventLink, Long> {

    @Query("select l from CalendarEventLink l join fetch l.task t join fetch t.project where l.user.id = :userId")
    List<CalendarEventLink> forUser(@Param("userId") Long userId);

    /** What belongs in the user's calendar: open tasks assigned to them that have a due date. */
    @Query("""
            select t from Task t join fetch t.project where t.assignee.id = :userId and t.dueDate is not null
              and t.status <> com.fakejira.task.TaskStatus.DONE and t.archivedAt is null""")
    List<com.fakejira.task.Task> dueTasks(@Param("userId") Long userId);

    Optional<CalendarEventLink> findByUserIdAndEventId(Long userId, String eventId);

    @Modifying
    @Query("delete from CalendarEventLink l where l.user.id = :userId")
    void deleteForUser(@Param("userId") Long userId);

    @Modifying
    @Query("delete from CalendarEventLink l where l.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
