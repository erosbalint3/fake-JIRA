package com.fakejira.webhook;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    List<WebhookDelivery> findByWebhookIdOrderByIdDesc(Long webhookId, Pageable pageable);

    @Modifying
    @Query("delete from WebhookDelivery d where d.webhookId = :webhookId and d.id < :oldestKept")
    void trim(@Param("webhookId") Long webhookId, @Param("oldestKept") Long oldestKept);

    @Modifying
    @Query("delete from WebhookDelivery d where d.webhookId = :webhookId")
    void deleteForWebhook(@Param("webhookId") Long webhookId);
}
