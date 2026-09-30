package com.fakejira.cluster;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * With several instances, each scheduled job (backups, digests, reminders, retention…) runs on only one of them per
 * firing: before running, an instance claims the job in Redis. Jobs marked {@link PerInstance} run everywhere.
 */
@Configuration
public class ClusterScheduling implements SchedulingConfigurer {

    /** How long a claim blocks the other instances; every job runs at most about this often. */
    static final Duration HOLD = Duration.ofSeconds(20);

    private final Cluster cluster;

    public ClusterScheduling(Cluster cluster) {
        this.cluster = cluster;
    }

    @Override
    public void configureTasks(@NonNull ScheduledTaskRegistrar registrar) {
        if (!cluster.enabled()) {
            return; // one instance: Spring's usual scheduler, unchanged
        }
        ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();
        pool.setPoolSize(1);
        pool.setThreadNamePrefix("scheduling-");
        pool.initialize();
        registrar.setTaskScheduler(new Claiming(pool, cluster));
    }

    /** Wraps each job so that it only runs on the instance that claims it. */
    static Runnable claimed(Runnable task, Cluster cluster) {
        // Spring wraps @Scheduled methods; their description is "package.Class.method".
        String name = task instanceof ScheduledMethodRunnable m
                ? m.getMethod().getDeclaringClass().getName() + "." + m.getMethod().getName() : task.toString();
        if (!cluster.enabled() || perInstance(name)) {
            return task;
        }
        return () -> {
            if (cluster.claim("job:" + name, HOLD)) {
                task.run();
            }
        };
    }

    static boolean perInstance(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        try {
            Class<?> type = Class.forName(name.substring(0, dot), false, ClusterScheduling.class.getClassLoader());
            String method = name.substring(dot + 1);
            for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
                if (m.getName().equals(method) && m.isAnnotationPresent(PerInstance.class)) {
                    return true;
                }
            }
        } catch (ClassNotFoundException | LinkageError e) {
            // not a scheduled method we know
        }
        return false;
    }

    record Claiming(TaskScheduler delegate, Cluster cluster) implements TaskScheduler {

        @Override
        public ScheduledFuture<?> schedule(@NonNull Runnable task, @NonNull Trigger trigger) {
            return delegate.schedule(claimed(task, cluster), trigger);
        }

        @Override
        public ScheduledFuture<?> schedule(@NonNull Runnable task, @NonNull Instant startTime) {
            return delegate.schedule(claimed(task, cluster), startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(@NonNull Runnable task, @NonNull Instant startTime, @NonNull Duration period) {
            return delegate.scheduleAtFixedRate(claimed(task, cluster), startTime, period);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(@NonNull Runnable task, @NonNull Duration period) {
            return delegate.scheduleAtFixedRate(claimed(task, cluster), period);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(@NonNull Runnable task, @NonNull Instant startTime, @NonNull Duration delay) {
            return delegate.scheduleWithFixedDelay(claimed(task, cluster), startTime, delay);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(@NonNull Runnable task, @NonNull Duration delay) {
            return delegate.scheduleWithFixedDelay(claimed(task, cluster), delay);
        }
    }
}
