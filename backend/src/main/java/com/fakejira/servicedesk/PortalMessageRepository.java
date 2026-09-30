package com.fakejira.servicedesk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PortalMessageRepository extends JpaRepository<PortalMessage, Long> {

    @Query("select m from PortalMessage m left join fetch m.author where m.request.id = :requestId order by m.createdAt, m.id")
    List<PortalMessage> forRequest(@Param("requestId") Long requestId);

    @Modifying
    @Query("delete from PortalMessage m where m.request.id = :requestId")
    void deleteForRequest(@Param("requestId") Long requestId);
}
