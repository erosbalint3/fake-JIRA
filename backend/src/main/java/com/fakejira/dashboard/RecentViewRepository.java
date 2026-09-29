package com.fakejira.dashboard;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RecentViewRepository extends JpaRepository<RecentView, Long> {

    Optional<RecentView> findByUserIdAndTaskId(Long userId, Long taskId);

    List<RecentView> findByUserIdOrderByViewedAtDesc(Long userId, Pageable pageable);

    @Modifying
    @Query("delete from RecentView r where r.taskId = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);

    @Modifying
    @Query("delete from RecentView r where r.userId = :userId")
    void deleteForUser(@Param("userId") Long userId);
}
