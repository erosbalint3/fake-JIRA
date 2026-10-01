package com.fakejira.personal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PrivateItemRepository extends JpaRepository<PrivateItem, Long> {

    List<PrivateItem> findByUserIdAndTaskIdOrderByPositionAscIdAsc(Long userId, Long taskId);

    Optional<PrivateItem> findByIdAndUserId(Long id, Long userId);

    @Query("select coalesce(max(i.position), -1) from PrivateItem i where i.user.id = :userId and i.task.id = :taskId")
    int maxPosition(@Param("userId") Long userId, @Param("taskId") Long taskId);
}
