package com.fakejira.integration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IssueLinkRepository extends JpaRepository<IssueLink, Long> {

    Optional<IssueLink> findByRepoIgnoreCaseAndNumber(String repo, int number);

    @Query("select l from IssueLink l join fetch l.task t join fetch t.project where t.id = :taskId")
    Optional<IssueLink> findByTaskId(@Param("taskId") Long taskId);

    @Modifying
    @Query("delete from IssueLink l where l.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
