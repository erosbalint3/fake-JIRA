package com.fakejira.epic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EpicRepository extends JpaRepository<Epic, Long> {

    List<Epic> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    long countByProjectId(Long projectId);
}
