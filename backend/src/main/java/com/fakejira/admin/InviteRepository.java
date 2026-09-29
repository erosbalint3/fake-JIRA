package com.fakejira.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InviteRepository extends JpaRepository<Invite, Long> {

    Optional<Invite> findByCode(String code);

    List<Invite> findAllByOrderByCreatedAtDesc();

    List<Invite> findByCreatedByIdOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("delete from Invite i where i.project.id = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
