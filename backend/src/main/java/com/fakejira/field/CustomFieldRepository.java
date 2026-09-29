package com.fakejira.field;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CustomFieldRepository extends JpaRepository<CustomField, Long> {

    List<CustomField> findByProjectIdOrderByPositionAscIdAsc(Long projectId);

    @Query("select count(f) > 0 from CustomField f where lower(f.name) = lower(:name) and f.project.id in :projectIds")
    boolean existsNamed(@Param("name") String name, @Param("projectIds") Collection<Long> projectIds);
}
