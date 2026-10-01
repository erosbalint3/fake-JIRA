package com.fakejira.sprint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SprintCapacityRepository extends JpaRepository<SprintCapacity, Long> {

    List<SprintCapacity> findBySprintId(Long sprintId);

    Optional<SprintCapacity> findBySprintIdAndUserId(Long sprintId, Long userId);

    @Modifying
    @Query("delete from SprintCapacity c where c.sprint.id in (select s.id from Sprint s where s.project.id = :projectId)")
    void deleteForProject(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from SprintCapacity c where c.user.id = :userId and c.sprint.id in "
            + "(select s.id from Sprint s where s.project.id = :projectId)")
    void deleteForMember(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Modifying
    @Query("delete from SprintCapacity c where c.sprint.id = :sprintId")
    void deleteForSprint(@Param("sprintId") Long sprintId);
}
