package com.fakejira.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface HealthVoteRepository extends JpaRepository<HealthVote, Long> {

    @Query("select v from HealthVote v join fetch v.user where v.check.id = :checkId")
    List<HealthVote> forCheck(@Param("checkId") Long checkId);

    @Query("select v from HealthVote v join fetch v.check where v.check.id in :checkIds")
    List<HealthVote> forChecks(@Param("checkIds") Collection<Long> checkIds);

    @Modifying
    @Query("delete from HealthVote v where v.check.id = :checkId")
    void deleteForCheck(@Param("checkId") Long checkId);

    @Modifying
    @Query("delete from HealthVote v where v.check.id in (select h.id from HealthCheck h where h.project.id = :projectId)")
    void deleteForProject(@Param("projectId") Long projectId);
}
