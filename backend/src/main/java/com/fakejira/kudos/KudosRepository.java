package com.fakejira.kudos;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface KudosRepository extends JpaRepository<Kudos, Long> {

    @Query("select k from Kudos k join fetch k.from join fetch k.to where k.project.id in :projects order by k.createdAt desc")
    List<Kudos> recent(@Param("projects") Collection<Long> projectIds, Pageable page);

    List<Kudos> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    long countByToId(Long userId);

    @Modifying
    @Query("update Kudos k set k.task = null where k.task.id = :taskId")
    void detachTask(@Param("taskId") Long taskId);

    @Modifying
    @Query("delete from Kudos k where k.project.id = :projectId")
    void deleteForProject(@Param("projectId") Long projectId);
}
