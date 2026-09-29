package com.fakejira.share;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShareLinkRepository extends JpaRepository<ShareLink, Long> {

    Optional<ShareLink> findByToken(String token);

    List<ShareLink> findByTaskIdOrderByIdDesc(Long taskId);

    @Modifying
    @Query("delete from ShareLink s where s.task.id = :taskId")
    void deleteForTask(@Param("taskId") Long taskId);
}
