package com.fakejira.personal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    @Query("select r from Reminder r left join fetch r.task where r.user.id = :userId and r.sentAt is null order by r.remindAt")
    List<Reminder> pending(@Param("userId") Long userId);

    @Query("select r from Reminder r where r.user.id = :userId and r.task.id = :taskId and r.sentAt is null order by r.remindAt")
    List<Reminder> pendingForTask(@Param("userId") Long userId, @Param("taskId") Long taskId);

    @Query("select r from Reminder r join fetch r.user left join fetch r.task where r.sentAt is null and r.remindAt <= :now")
    List<Reminder> due(@Param("now") Instant now);

    Optional<Reminder> findByIdAndUserId(Long id, Long userId);
}
