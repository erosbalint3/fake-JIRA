package com.fakejira.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookRepository extends JpaRepository<Webhook, Long> {

    List<Webhook> findByProjectIdOrderByIdAsc(Long projectId);
}
