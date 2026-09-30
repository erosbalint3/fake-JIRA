package com.fakejira.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, Long> {

    Optional<RoleAssignment> findByProjectIdAndUserId(Long projectId, Long userId);

    List<RoleAssignment> findByProjectId(Long projectId);

    @Modifying
    @Query("delete from RoleAssignment a where a.projectId = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);

    @Modifying
    @Query("delete from RoleAssignment a where a.role.id = :roleId")
    void deleteForRole(@Param("roleId") Long roleId);

    @Modifying
    @Query("delete from RoleAssignment a where a.projectId = :projectId and a.userId = :userId")
    void deleteFor(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Modifying
    @Query("delete from RoleAssignment a where a.userId = :userId")
    void deleteForUser(@Param("userId") Long userId);
}
