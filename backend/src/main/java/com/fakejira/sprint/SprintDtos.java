package com.fakejira.sprint;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class SprintDtos {

    private SprintDtos() {
    }

    public record SprintRequest(
            @Size(max = 80, message = "Name must be at most 80 characters") String name,
            @Size(max = 500, message = "Goal must be at most 500 characters") String goal,
            LocalDate startDate,
            LocalDate endDate) {
    }

    public record SprintResponse(
            Long id,
            String name,
            String goal,
            SprintState state,
            LocalDate startDate,
            LocalDate endDate,
            Instant completedAt,
            int carriedOver) {

        public static SprintResponse of(Sprint sprint) {
            return new SprintResponse(sprint.getId(), sprint.getName(), sprint.getGoal(), sprint.getState(),
                    sprint.getStartDate(), sprint.getEndDate(), sprint.getCompletedAt(), sprint.getCarriedOver());
        }
    }

    /** {@code remaining} is null for days that have not happened yet. */
    public record BurndownPoint(LocalDate date, Integer remaining, double ideal) {
    }

    public record Burndown(SprintResponse sprint, int total, int done, List<BurndownPoint> points) {
    }
}
