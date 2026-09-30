package com.fakejira.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** The inbox: neither snoozed nor done, newest (or most recently woken) first. */
    @Query("select n from Notification n where n.recipient.id = :recipientId and n.snoozedUntil is null"
            + " and n.archivedAt is null order by coalesce(n.activeAt, n.createdAt) desc, n.id desc")
    List<Notification> inbox(@Param("recipientId") Long recipientId, Pageable page);

    @Query("select n from Notification n where n.recipient.id = :recipientId and n.snoozedUntil is not null"
            + " order by n.snoozedUntil asc")
    List<Notification> snoozed(@Param("recipientId") Long recipientId, Pageable page);

    @Query("select n from Notification n where n.recipient.id = :recipientId and n.archivedAt is not null"
            + " order by n.archivedAt desc")
    List<Notification> done(@Param("recipientId") Long recipientId, Pageable page);

    default List<Notification> findTop100ByRecipientIdOrderByCreatedAtDesc(Long recipientId) {
        return inbox(recipientId, Pageable.ofSize(100));
    }

    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    List<Notification> findByIdInAndRecipientId(Collection<Long> ids, Long recipientId);

    @Query("select count(n) from Notification n where n.recipient.id = :recipientId and n.read = false"
            + " and n.snoozedUntil is null and n.archivedAt is null")
    long countByRecipientIdAndReadFalse(@Param("recipientId") Long recipientId);

    List<Notification> findByRecipientIdAndCreatedAtAfterOrderByCreatedAtAsc(Long recipientId, Instant after);

    @Query("select n from Notification n join fetch n.recipient where n.snoozedUntil <= :now")
    List<Notification> dueToWake(@Param("now") Instant now);

    @Modifying
    @Query("update Notification n set n.read = true where n.recipient.id = :recipientId and n.read = false")
    int markAllRead(@Param("recipientId") Long recipientId);

    /** "Done" for everything read in the inbox. */
    @Modifying
    @Query("update Notification n set n.archivedAt = :now where n.recipient.id = :recipientId and n.read = true"
            + " and n.archivedAt is null and n.snoozedUntil is null")
    int archiveRead(@Param("recipientId") Long recipientId, @Param("now") Instant now);
}
