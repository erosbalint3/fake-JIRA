package com.fakejira.wiki;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WikiRevisionRepository extends JpaRepository<WikiRevision, Long> {

    @Query("select r from WikiRevision r join fetch r.author where r.page.id = :pageId order by r.version desc")
    List<WikiRevision> history(@Param("pageId") Long pageId);

    Optional<WikiRevision> findByPageIdAndVersion(Long pageId, int version);

    @Modifying
    @Query("delete from WikiRevision r where r.page.id = :pageId")
    void deleteForPage(@Param("pageId") Long pageId);
}
