package com.fakejira.field;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CustomFieldValueRepository extends JpaRepository<CustomFieldValue, Long> {

    @Query("select v from CustomFieldValue v join fetch v.field where v.task.id = :taskId")
    List<CustomFieldValue> findForTask(@Param("taskId") Long taskId);

    @Query("select v from CustomFieldValue v join fetch v.field where v.task.id in :taskIds")
    List<CustomFieldValue> findForTasks(@Param("taskIds") Collection<Long> taskIds);

    Optional<CustomFieldValue> findByTaskIdAndFieldId(Long taskId, Long fieldId);

    @Modifying
    @Query("delete from CustomFieldValue v where v.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);

    @Modifying
    @Query("delete from CustomFieldValue v where v.field.id = :fieldId")
    void deleteForField(@Param("fieldId") Long fieldId);
}
