package com.fakejira.wiki;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WikiPageRepository extends JpaRepository<WikiPage, Long> {

    List<WikiPage> findByProjectIdOrderByTitleAsc(Long projectId);

    Optional<WikiPage> findByProjectIdAndSlug(Long projectId, String slug);

    boolean existsByProjectIdAndSlug(Long projectId, String slug);

    List<WikiPage> findByParentId(Long parentId);

    @Query("select p from WikiPage p where p.project.id in :projects and (lower(p.title) like :w escape '\\' "
            + "or lower(p.body) like :w escape '\\')")
    List<WikiPage> search(@Param("projects") Collection<Long> projectIds, @Param("w") String pattern,
                          org.springframework.data.domain.Pageable page);

    @Query("select p from WikiPage p where p.project.id = :projectId and upper(p.body) like :key")
    List<WikiPage> mentioning(@Param("projectId") Long projectId, @Param("key") String pattern);
}
