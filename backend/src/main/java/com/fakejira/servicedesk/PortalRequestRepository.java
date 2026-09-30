package com.fakejira.servicedesk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PortalRequestRepository extends JpaRepository<PortalRequest, Long> {

    @Query("select r from PortalRequest r join fetch r.task t join fetch t.project where r.token = :token")
    Optional<PortalRequest> findByToken(@Param("token") String token);

    Optional<PortalRequest> findByTaskId(Long taskId);
}
