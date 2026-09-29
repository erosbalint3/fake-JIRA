package com.fakejira.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    @Query("""
            select e from AuditEvent e
            where (:action is null or e.action = :action)
              and (:q is null or lower(e.actorName) like :q or lower(e.target) like :q or lower(e.details) like :q
                   or e.ip like :q)
            order by e.createdAt desc, e.id desc""")
    Page<AuditEvent> search(String action, String q, Pageable pageable);

    @Query("select distinct e.action from AuditEvent e order by e.action")
    List<String> actions();

    @Modifying
    @Query("delete from AuditEvent e where e.createdAt < :before")
    int deleteOlderThan(Instant before);
}
