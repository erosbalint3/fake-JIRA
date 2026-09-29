package com.fakejira.planning;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PokerRoundRepository extends JpaRepository<PokerRound, Long> {

    Optional<PokerRound> findByTaskId(Long taskId);
}
