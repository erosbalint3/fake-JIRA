package com.fakejira.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskTemplateRepository extends JpaRepository<TaskTemplate, Long> {

    List<TaskTemplate> findByProjectIdOrderByNameAsc(Long projectId);
}
