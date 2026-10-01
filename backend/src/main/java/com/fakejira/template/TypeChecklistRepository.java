package com.fakejira.template;

import com.fakejira.task.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TypeChecklistRepository extends JpaRepository<TypeChecklist, Long> {

    List<TypeChecklist> findByProjectId(Long projectId);

    Optional<TypeChecklist> findByProjectIdAndType(Long projectId, TaskType type);

    @Modifying
    @Query("delete from TypeChecklist t where t.project.id = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
