package com.fakejira.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    @Query("select s from UserSession s where s.user.id = :userId and s.revokedAt is null and s.expiresAt > :now order by s.lastSeenAt desc")
    List<UserSession> findActive(Long userId, Instant now);

    @Modifying
    @Query("delete from UserSession s where s.expiresAt < :before or s.revokedAt < :before")
    int deleteEnded(Instant before);

    @Modifying
    @Query("delete from UserSession s where s.user.id = :userId")
    void deleteForUser(Long userId);
}
