package com.fakejira.integration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GithubRepoLinkRepository extends JpaRepository<GithubRepoLink, Long> {

    Optional<GithubRepoLink> findByProjectId(Long projectId);

    @Modifying
    @Query("delete from GithubRepoLink l where l.projectId = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
