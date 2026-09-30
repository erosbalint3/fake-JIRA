package com.fakejira.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface KnownDeviceRepository extends JpaRepository<KnownDevice, Long> {

    Optional<KnownDevice> findByUserIdAndFingerprint(Long userId, String fingerprint);

    boolean existsByUserId(Long userId);

    @Modifying
    @Query("delete from KnownDevice d where d.userId = :userId")
    void deleteForUser(@Param("userId") Long userId);

    @Modifying
    @Query("delete from KnownDevice d where d.lastSeen < :before")
    int deleteUnusedSince(@Param("before") Instant before);
}
