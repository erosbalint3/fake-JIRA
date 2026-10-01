package com.fakejira.servicedesk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RequestTypeRepository extends JpaRepository<RequestType, Long> {

    List<RequestType> findByProjectIdOrderByPositionAscIdAsc(Long projectId);

    @Modifying
    @Query("delete from RequestType t where t.projectId = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
