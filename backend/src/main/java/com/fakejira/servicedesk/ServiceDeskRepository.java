package com.fakejira.servicedesk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ServiceDeskRepository extends JpaRepository<ServiceDesk, Long> {

    Optional<ServiceDesk> findByProjectId(Long projectId);

    @Modifying
    @Query("delete from ServiceDesk d where d.projectId = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
