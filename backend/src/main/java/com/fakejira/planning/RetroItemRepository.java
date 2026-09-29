package com.fakejira.planning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RetroItemRepository extends JpaRepository<RetroItem, Long> {

    List<RetroItem> findBySprintIdOrderByCreatedAtAsc(Long sprintId);

    List<RetroItem> findBySprintProjectId(Long projectId);
}
