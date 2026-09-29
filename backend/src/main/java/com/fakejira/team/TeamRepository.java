package com.fakejira.team;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    List<Team> findAllByOrderByNameAsc();

    Optional<Team> findByHandleIgnoreCase(String handle);

    boolean existsByHandleIgnoreCase(String handle);

    @Query("select t from Team t where lower(t.handle) in :handles")
    List<Team> findByHandles(@Param("handles") Collection<String> handles);

    @Query("select t from Team t join t.members m where m.id = :userId")
    List<Team> findForMember(@Param("userId") Long userId);
}
