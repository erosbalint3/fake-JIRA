package com.fakejira.goal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KeyResultRepository extends JpaRepository<KeyResult, Long> {

    List<KeyResult> findByGoalIdOrderByPositionAscIdAsc(Long goalId);

    @Query("select distinct k from KeyResult k join k.epics e where e.id = :epicId")
    List<KeyResult> findByEpic(@Param("epicId") Long epicId);
}
