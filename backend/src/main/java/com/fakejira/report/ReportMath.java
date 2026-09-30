package com.fakejira.report;

import com.fakejira.task.StatusChange;
import com.fakejira.task.TaskStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Small helpers shared by the reports. */
final class ReportMath {

    private ReportMath() {
    }

    static double days(Instant from, Instant to) {
        return Math.round(Duration.between(from, to).toMinutes() / 144.0) / 10.0;
    }

    static double hours(Instant from, Instant to) {
        return Math.round(Duration.between(from, to).toMinutes() / 6.0) / 10.0;
    }

    static Double average(List<Double> values) {
        return values.isEmpty() ? null
                : Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
    }

    /** Nearest-rank percentile of a sorted list. */
    static Double percentile(List<Double> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    static int percentileInt(int[] sorted, double p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    /** When work on the task started: its first move to In progress or In review. */
    static Instant startedAt(List<StatusChange> history) {
        return history.stream()
                .filter(c -> c.getToStatus() == TaskStatus.IN_PROGRESS || c.getToStatus() == TaskStatus.IN_REVIEW)
                .map(StatusChange::getChangedAt).findFirst().orElse(null);
    }
}
