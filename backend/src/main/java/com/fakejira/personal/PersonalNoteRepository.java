package com.fakejira.personal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PersonalNoteRepository extends JpaRepository<PersonalNote, Long> {

    Optional<PersonalNote> findByUserIdAndTaskId(Long userId, Long taskId);
}
