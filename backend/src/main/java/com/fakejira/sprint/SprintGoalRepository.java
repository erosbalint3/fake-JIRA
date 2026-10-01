package com.fakejira.sprint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SprintGoalRepository extends JpaRepository<SprintGoal, Long> {

    List<SprintGoal> findBySprintIdOrderByPositionAscIdAsc(Long sprintId);

    @Modifying
    @Query("delete from SprintGoal g where g.sprint.id in (select s.id from Sprint s where s.project.id = :projectId)")
    void deleteForProject(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from SprintGoal g where g.sprint.id = :sprintId")
    void deleteForSprint(@Param("sprintId") Long sprintId);
}
