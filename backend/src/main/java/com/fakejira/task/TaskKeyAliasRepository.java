package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TaskKeyAliasRepository extends JpaRepository<TaskKeyAlias, Long> {

    Optional<TaskKeyAlias> findByOldKey(String oldKey);

    @Modifying
    @Query("delete from TaskKeyAlias a where a.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
