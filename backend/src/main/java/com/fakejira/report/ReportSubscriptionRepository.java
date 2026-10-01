package com.fakejira.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReportSubscriptionRepository extends JpaRepository<ReportSubscription, Long> {

    List<ReportSubscription> findByUserIdOrderByIdAsc(Long userId);

    Optional<ReportSubscription> findByIdAndUserId(Long id, Long userId);

    @Query("select s from ReportSubscription s join fetch s.user")
    List<ReportSubscription> findAllWithUser();

    List<ReportSubscription> findByKindAndTarget(String kind, String target);
}
