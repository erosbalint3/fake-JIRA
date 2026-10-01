package com.fakejira.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CalendarConnectionRepository extends JpaRepository<CalendarConnection, Long> {

    Optional<CalendarConnection> findByUserId(Long userId);

    @Query("select c from CalendarConnection c join fetch c.user")
    List<CalendarConnection> findAllWithUser();
}
