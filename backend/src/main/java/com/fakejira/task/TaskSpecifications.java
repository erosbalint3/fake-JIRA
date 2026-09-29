package com.fakejira.task;

import com.fakejira.common.ApiException;
import com.fakejira.user.User;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TaskSpecifications {

    private TaskSpecifications() {
    }

    /** Only tasks in projects {@code user} is a member of, narrowed by {@code filter}. */
    static Specification<Task> visibleTo(User user, TaskFilter filter) {
        return (root, query, cb) -> {
            query.distinct(true);
            List<Predicate> where = new ArrayList<>();
            Join<Object, Object> project = root.join("project");
            Join<Object, Object> member = project.join("members");
            where.add(cb.equal(member.get("id"), user.getId()));

            if (filter.project() != null && !filter.project().isBlank()) {
                where.add(cb.equal(project.get("key"), filter.project().trim().toUpperCase(Locale.ROOT)));
            }
            TaskScope scope = filter.scope() == null ? TaskScope.ALL : filter.scope();
            switch (scope) {
                case AVAILABLE -> {
                    where.add(cb.isNull(root.get("assignee")));
                    where.add(cb.notEqual(root.get("status"), TaskStatus.DONE));
                }
                case MINE -> where.add(cb.equal(root.get("assignee").get("id"), user.getId()));
                case REPORTED -> where.add(cb.equal(root.get("reporter").get("id"), user.getId()));
                case ALL -> {
                }
            }
            if (filter.priority() != null) {
                where.add(cb.equal(root.get("priority"), filter.priority()));
            }
            if (filter.status() != null) {
                where.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.q() != null && !filter.q().isBlank()) {
                String pattern = "%" + filter.q().trim().toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern)));
            }
            if (filter.label() != null && !filter.label().isBlank()) {
                Join<Object, Object> label = root.join("labels", JoinType.INNER);
                where.add(cb.equal(label, filter.label().trim().toLowerCase(Locale.ROOT)));
            }
            if (filter.sprint() != null && !filter.sprint().isBlank()) {
                if ("backlog".equalsIgnoreCase(filter.sprint())) {
                    where.add(cb.isNull(root.get("sprint")));
                } else {
                    where.add(cb.equal(root.get("sprint").get("id"), parseId(filter.sprint(), "sprint")));
                }
            }
            if (filter.assignee() != null && !filter.assignee().isBlank()) {
                switch (filter.assignee().toLowerCase(Locale.ROOT)) {
                    case "none" -> where.add(cb.isNull(root.get("assignee")));
                    case "me" -> where.add(cb.equal(root.get("assignee").get("id"), user.getId()));
                    default -> where.add(cb.equal(root.get("assignee").get("id"), parseId(filter.assignee(), "assignee")));
                }
            }
            if (filter.epic() != null && !filter.epic().isBlank()) {
                if ("none".equalsIgnoreCase(filter.epic())) {
                    where.add(cb.isNull(root.get("epic")));
                } else {
                    where.add(cb.equal(root.get("epic").get("id"), parseId(filter.epic(), "epic")));
                }
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static Long parseId(String value, String name) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid " + name + " filter.");
        }
    }
}
