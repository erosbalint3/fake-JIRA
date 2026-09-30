package com.fakejira.preference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    List<UserPreference> findByUserId(Long userId);

    Optional<UserPreference> findByUserIdAndKey(Long userId, String key);

    long countByUserId(Long userId);

    @Modifying
    @Query("delete from UserPreference p where p.userId = :userId")
    void deleteForUser(@Param("userId") Long userId);
}
