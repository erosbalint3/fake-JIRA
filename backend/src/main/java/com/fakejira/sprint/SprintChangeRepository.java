package com.fakejira.sprint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SprintChangeRepository extends JpaRepository<SprintChange, Long> {

    List<SprintChange> findBySprintIdOrderByChangedAtAsc(Long sprintId);

    @Modifying
    @Query("delete from SprintChange c where c.sprint.id = :sprintId")
    void deleteForSprint(@Param("sprintId") Long sprintId);
}
