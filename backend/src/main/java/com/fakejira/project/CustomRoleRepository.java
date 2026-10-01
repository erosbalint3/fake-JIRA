package com.fakejira.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CustomRoleRepository extends JpaRepository<CustomRole, Long> {

    List<CustomRole> findByProjectIdOrderByNameAsc(Long projectId);

    @Modifying
    @Query("delete from CustomRole r where r.project.id = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
