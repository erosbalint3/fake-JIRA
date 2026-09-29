package com.fakejira.automation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AutomationRuleRepository extends JpaRepository<AutomationRule, Long> {

    List<AutomationRule> findByProjectIdOrderByIdAsc(Long projectId);

    List<AutomationRule> findByProjectIdAndEnabledTrueAndTrigger(Long projectId, AutomationRule.Trigger trigger);

    List<AutomationRule> findByEnabledTrueAndTrigger(AutomationRule.Trigger trigger);
}
