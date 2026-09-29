package com.fakejira.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SavedFilterRepository extends JpaRepository<SavedFilter, Long> {

    @Query("""
            select f from SavedFilter f join fetch f.owner
            where f.project.id = :projectId and (f.owner.id = :userId or f.shared = true)
            order by f.name
            """)
    List<SavedFilter> visibleIn(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Modifying
    @Query("delete from SavedFilter f where f.project.id = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
