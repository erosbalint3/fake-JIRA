package com.fakejira.personal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RunningTimerRepository extends JpaRepository<RunningTimer, Long> {

    Optional<RunningTimer> findByUserId(Long userId);

    @Query("select t from RunningTimer t join fetch t.user join fetch t.task where t.remindedAt is null and t.startedAt <= :before")
    List<RunningTimer> runningSince(@Param("before") Instant before);
}
