package com.fakejira.board;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BoardColumnRepository extends JpaRepository<BoardColumn, Long> {

    List<BoardColumn> findByProjectIdOrderByPositionAscIdAsc(Long projectId);
}
