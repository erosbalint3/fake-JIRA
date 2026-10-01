package com.fakejira.personal;

import com.fakejira.task.Task;
import com.fakejira.task.TaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TodayPickRepository extends JpaRepository<TodayPick, Long> {

    @Query("select p from TodayPick p join fetch p.task t join fetch t.project where p.user.id = :userId and p.day = :day"
            + " order by p.position, p.id")
    List<TodayPick> forDay(@Param("userId") Long userId, @Param("day") LocalDate day);

    Optional<TodayPick> findByUserIdAndTaskIdAndDay(Long userId, Long taskId, LocalDate day);

    /** The most recent earlier day with picks, for carrying unfinished work over. */
    @Query("select max(p.day) from TodayPick p where p.user.id = :userId and p.day < :day")
    LocalDate previousDay(@Param("userId") Long userId, @Param("day") LocalDate day);

    /** Open work assigned to (or helped on by) the user that is due soon, overdue or in progress. */
    @Query("""
            select distinct t from Task t join fetch t.project pr left join t.helpers h
            where (t.assignee.id = :userId or h.id = :userId) and t.status <> :done and t.archivedAt is null
              and (t.status = :inProgress or (t.dueDate is not null and t.dueDate <= :dueBy))
              and exists (select m from Project p join p.members m where p = pr and m.id = :userId)
            order by t.dueDate asc nulls last, t.updatedAt desc""")
    List<Task> suggestions(@Param("userId") Long userId, @Param("dueBy") LocalDate dueBy,
                           @Param("done") TaskStatus done, @Param("inProgress") TaskStatus inProgress, Pageable page);

    /** Tasks the user finished in a time window (assignee or helper). */
    @Query("""
            select distinct t from Task t join fetch t.project left join t.helpers h
            where (t.assignee.id = :userId or h.id = :userId) and t.completedAt >= :from and t.completedAt < :to
            order by t.completedAt""")
    List<Task> completedBetween(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select coalesce(sum(e.minutes), 0) from TimeEntry e where e.user.id = :userId and e.workDate = :day")
    long minutesLogged(@Param("userId") Long userId, @Param("day") LocalDate day);

    @Query("select count(c) from Comment c where c.author.id = :userId and c.createdAt >= :from and c.createdAt < :to")
    long commentsBetween(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);
}
