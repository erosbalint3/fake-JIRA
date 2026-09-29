package com.fakejira.release;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReleaseRepository extends JpaRepository<Release, Long> {

    List<Release> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    boolean existsByProjectIdAndNameIgnoreCase(Long projectId, String name);
}
