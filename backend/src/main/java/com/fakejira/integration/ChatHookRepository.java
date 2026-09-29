package com.fakejira.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatHookRepository extends JpaRepository<ChatHook, Long> {

    List<ChatHook> findByProjectIdOrderByIdAsc(Long projectId);
}
