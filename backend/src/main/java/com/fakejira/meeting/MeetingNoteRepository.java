package com.fakejira.meeting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeetingNoteRepository extends JpaRepository<MeetingNote, Long> {

    List<MeetingNote> findByProjectIdOrderByMeetingDateDescIdDesc(Long projectId);

    List<MeetingNote> findBySprintIdOrderByMeetingDateDescIdDesc(Long sprintId);
}
