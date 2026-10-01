package com.fakejira.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    List<NotificationPreference> findByUserId(Long userId);

    Optional<NotificationPreference> findByUserIdAndProjectIdIsNull(Long userId);

    Optional<NotificationPreference> findByUserIdAndProjectId(Long userId, Long projectId);

    @Modifying
    @Query("delete from NotificationPreference p where p.projectId = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from NotificationPreference p where p.projectId = :projectId and p.user.id = :userId")
    void deleteForMember(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
