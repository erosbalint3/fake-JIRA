package com.fakejira.automation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RuleFiringRepository extends JpaRepository<RuleFiring, Long> {

    List<RuleFiring> findByRuleId(Long ruleId);

    @Modifying
    @Query("delete from RuleFiring f where f.ruleId = :ruleId")
    void deleteForRule(@Param("ruleId") Long ruleId);

    @Modifying
    @Query("delete from RuleFiring f where f.taskId = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
