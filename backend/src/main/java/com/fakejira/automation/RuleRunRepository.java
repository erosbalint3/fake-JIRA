package com.fakejira.automation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RuleRunRepository extends JpaRepository<RuleRun, Long> {

    List<RuleRun> findByRuleIdOrderByIdDesc(Long ruleId, Pageable pageable);

    @Modifying
    @Query("delete from RuleRun r where r.ruleId = :ruleId and r.id < :oldestKept")
    void trim(@Param("ruleId") Long ruleId, @Param("oldestKept") Long oldestKept);

    @Modifying
    @Query("delete from RuleRun r where r.ruleId = :ruleId")
    void deleteForRule(@Param("ruleId") Long ruleId);
}
