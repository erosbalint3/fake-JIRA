package com.fakejira.decision;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PollVoteRepository extends JpaRepository<PollVote, Long> {

    @Query("select v from PollVote v join fetch v.user where v.poll.id = :pollId")
    List<PollVote> findForPoll(@Param("pollId") Long pollId);

    @Modifying
    @Query("delete from PollVote v where v.poll.id = :pollId and v.user.id = :userId")
    void deleteMine(@Param("pollId") Long pollId, @Param("userId") Long userId);

    @Modifying
    @Query("delete from PollVote v where v.poll.id = :pollId")
    void deleteForPoll(@Param("pollId") Long pollId);
}
