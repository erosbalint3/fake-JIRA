package com.fakejira.search;

import com.fakejira.search.Fql.Clause;
import com.fakejira.search.Fql.FqlException;
import com.fakejira.search.Fql.Node;
import com.fakejira.search.Fql.Op;
import com.fakejira.search.Fql.Value;
import com.fakejira.task.Comment;
import com.fakejira.task.Task;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskType;
import com.fakejira.user.User;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Turns a parsed {@link Fql.Query} into a JPA specification and a sort order. */
public final class FqlCompiler {

    /** Field names (and aliases) the language understands, for errors and autocomplete. */
    public static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("project", "Project key"),
            Map.entry("key", "Task key, e.g. WEB-12"),
            Map.entry("status", "todo, \"in progress\", review, done"),
            Map.entry("priority", "low, medium, high, critical (supports < >)"),
            Map.entry("type", "task, bug, story, spike"),
            Map.entry("assignee", "username, me, EMPTY, membersOf(team)"),
            Map.entry("reporter", "username, me"),
            Map.entry("watcher", "username, me"),
            Map.entry("sprint", "name or id, active, open, EMPTY"),
            Map.entry("epic", "epic name, EMPTY"),
            Map.entry("release", "version name, unreleased, EMPTY"),
            Map.entry("label", "label, EMPTY"),
            Map.entry("text", "~ words in title, description or comments"),
            Map.entry("title", "~ words in the title"),
            Map.entry("description", "~ words in the description"),
            Map.entry("comment", "~ words in comments"),
            Map.entry("due", "date: 2026-10-01, today, -7d, +2w, startOfWeek, EMPTY"),
            Map.entry("created", "date"),
            Map.entry("updated", "date"),
            Map.entry("resolved", "date the task was done"),
            Map.entry("points", "story points (supports < >), EMPTY"),
            Map.entry("parent", "parent task key, EMPTY for top-level tasks"),
            Map.entry("resolution", "done, fixed, \"won't do\", duplicate, \"cannot reproduce\", EMPTY"),
            Map.entry("component", "component name, EMPTY"),
            Map.entry("helper", "username, me, EMPTY"),
            Map.entry("archived", "true or false (archived tasks are hidden unless asked for)"),
            Map.entry("start", "planned start date"),
            Map.entry("estimate", "original estimate in hours (supports < >), EMPTY"));

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("labels", "label"), Map.entry("fixversion", "release"), Map.entry("version", "release"),
            Map.entry("duedate", "due"), Map.entry("storypoints", "points"), Map.entry("sp", "points"),
            Map.entry("completed", "resolved"), Map.entry("done", "resolved"), Map.entry("summary", "title"),
            Map.entry("issuetype", "type"), Map.entry("watchers", "watcher"), Map.entry("comments", "comment"),
            Map.entry("components", "component"), Map.entry("helpers", "helper"), Map.entry("startdate", "start"),
            Map.entry("originalestimate", "estimate"));

    private static final Pattern RELATIVE = Pattern.compile("^([+-]?)(\\d+)([dwmy])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_KEY = Pattern.compile("^([A-Za-z][A-Za-z0-9]{1,9})-(\\d+)$");

    private final User user;
    private final ZoneId zone;
    private final Function<String, Set<Long>> teamMembers;
    private final java.util.function.Predicate<String> customField;

    /** {@code teamMembers} resolves {@code membersOf(name)}; it throws or returns null for unknown teams. */
    public FqlCompiler(User user, ZoneId zone, Function<String, Set<Long>> teamMembers) {
        this(user, zone, teamMembers, name -> false);
    }

    /** {@code customField} tells whether a name is a custom field in one of the user's projects. */
    public FqlCompiler(User user, ZoneId zone, Function<String, Set<Long>> teamMembers,
                       java.util.function.Predicate<String> customField) {
        this.user = user;
        this.zone = zone;
        this.teamMembers = teamMembers;
        this.customField = customField;
    }

    /** Projects where the user may read internal comments; elsewhere only public comments are searched. */
    private Set<Long> internalProjects = Set.of();

    public FqlCompiler internalCommentsIn(Set<Long> projectIds) {
        this.internalProjects = projectIds;
        return this;
    }

    public static String canonical(String field) {
        return ALIASES.getOrDefault(field, field);
    }

    /** Validates field names and values eagerly so errors point at the query, then builds the specification. */
    public Specification<Task> where(Node node) {
        if (node != null) {
            check(node);
        }
        return (root, query, cb) -> node == null ? cb.conjunction() : predicate(node, root, cb, query);
    }

    public Comparator<Task> order(List<Fql.Sort> sorts) {
        Comparator<Task> comparator = null;
        for (Fql.Sort sort : sorts) {
            Comparator<Task> next = switch (canonical(sort.field())) {
                case "priority" -> Comparator.comparing(Task::getPriority);
                case "status" -> Comparator.comparing(Task::getStatus);
                case "created" -> Comparator.comparing(Task::getCreatedAt);
                case "updated" -> Comparator.comparing(Task::getUpdatedAt);
                case "due" -> Comparator.comparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()));
                case "resolved" -> Comparator.comparing(Task::getCompletedAt, Comparator.nullsLast(Comparator.naturalOrder()));
                case "points" -> Comparator.comparing(Task::getStoryPoints, Comparator.nullsLast(Comparator.naturalOrder()));
                case "key" -> Comparator.comparing((Task t) -> t.getProject().getKey()).thenComparing(Task::getNumber);
                case "title" -> Comparator.comparing((Task t) -> t.getTitle().toLowerCase(Locale.ROOT));
                case "assignee" -> Comparator.comparing((Task t) -> t.getAssignee() == null ? null : t.getAssignee().getUsername(),
                        Comparator.nullsLast(Comparator.naturalOrder()));
                case "project" -> Comparator.comparing((Task t) -> t.getProject().getKey());
                case "type" -> Comparator.comparing(Task::getType);
                case "start" -> Comparator.comparing(Task::getStartDate, Comparator.nullsLast(Comparator.naturalOrder()));
                case "estimate" -> Comparator.comparing(Task::getEstimateMinutes, Comparator.nullsLast(Comparator.naturalOrder()));
                case "resolution" -> Comparator.comparing(Task::getResolution, Comparator.nullsLast(Comparator.naturalOrder()));
                default -> throw new FqlException("Cannot sort by " + sort.field() + ".", 0);
            };
            if (sort.descending()) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        Comparator<Task> fallback = Comparator.comparing(Task::getUpdatedAt).reversed();
        return comparator == null ? fallback : comparator.thenComparing(fallback);
    }

    // ------------------------------------------------------------------ validation

    private void check(Node node) {
        if (node instanceof Fql.And and) {
            check(and.left());
            check(and.right());
        } else if (node instanceof Fql.Or or) {
            check(or.left());
            check(or.right());
        } else if (node instanceof Fql.Not not) {
            check(not.inner());
        } else if (node instanceof Clause clause) {
            String field = canonical(clause.field());
            if (!FIELDS.containsKey(field) && customField.test(clause.field())) {
                if (Set.of(Op.GT, Op.GE, Op.LT, Op.LE).contains(clause.op())) {
                    throw new FqlException("Custom fields support =, !=, ~, IN and IS EMPTY.", clause.position());
                }
                return;
            }
            if (!FIELDS.containsKey(field)) {
                throw new FqlException("Unknown field '" + clause.field() + "'. Try: " + String.join(", ",
                        FIELDS.keySet().stream().sorted().toList()) + ".", clause.position());
            }
            // Resolving values throws positioned errors for bad ones.
            switch (field) {
                case "status" -> statuses(clause);
                case "priority" -> priorities(clause);
                case "type" -> clause.values().forEach(this::type);
                case "due", "start" -> clause.values().forEach(this::date);
                case "estimate" -> clause.values().forEach(this::number);
                case "resolution" -> clause.values().forEach(this::resolution);
                case "helper" -> clause.values().forEach(this::users);
                case "archived" -> clause.values().forEach(this::bool);
                case "created", "updated", "resolved" -> clause.values().stream().filter(v -> hours(v) == null).forEach(this::date);
                case "points" -> clause.values().forEach(this::number);
                case "assignee", "reporter", "watcher" -> clause.values().forEach(this::users);
                default -> {
                }
            }
            boolean textField = Set.of("text", "title", "description", "comment").contains(field);
            if (textField && clause.op() != Op.CONTAINS && clause.op() != Op.NOT_CONTAINS) {
                throw new FqlException("Use ~ (contains) or !~ with " + field + ".", clause.position());
            }
            if (!textField && (clause.op() == Op.CONTAINS || clause.op() == Op.NOT_CONTAINS)
                    && !Set.of("epic", "release", "sprint", "label", "component").contains(field)) {
                throw new FqlException("~ only works with text fields (text, title, description, comment) and names.",
                        clause.position());
            }
        }
    }

    // ------------------------------------------------------------------ predicates

    private Predicate predicate(Node node, Root<Task> root, CriteriaBuilder cb, jakarta.persistence.criteria.CriteriaQuery<?> query) {
        if (node instanceof Fql.And and) {
            return cb.and(predicate(and.left(), root, cb, query), predicate(and.right(), root, cb, query));
        }
        if (node instanceof Fql.Or or) {
            return cb.or(predicate(or.left(), root, cb, query), predicate(or.right(), root, cb, query));
        }
        if (node instanceof Fql.Not not) {
            return cb.not(predicate(not.inner(), root, cb, query));
        }
        return clause((Clause) node, root, cb, query);
    }

    private Predicate clause(Clause c, Root<Task> root, CriteriaBuilder cb, jakarta.persistence.criteria.CriteriaQuery<?> query) {
        String field = canonical(c.field());
        return switch (field) {
            case "project" -> named(c, cb, cb.lower(root.get("project").get("key")), null);
            case "key" -> key(c, root, cb);
            case "status" -> enumIn(c, cb, root.get("status"), statuses(c));
            case "priority" -> enumIn(c, cb, root.get("priority"), priorities(c));
            case "type" -> enumIn(c, cb, root.get("type"), c.values().stream().map(this::type).collect(Collectors.toSet()));
            case "assignee" -> person(c, cb, root.join("assignee", JoinType.LEFT));
            case "reporter" -> person(c, cb, root.join("reporter", JoinType.LEFT));
            case "watcher" -> watcher(c, root, cb, query);
            case "sprint" -> sprint(c, root, cb);
            case "epic" -> named(c, cb, cb.lower(root.join("epic", JoinType.LEFT).get("name")), root.get("epic"));
            case "release" -> release(c, root, cb);
            case "label" -> label(c, root, cb, query);
            case "text" -> text(c, cb, cb.or(like(cb, root.get("title"), c), like(cb, root.get("description"), c),
                    commentLike(c, root, cb, query)));
            case "title" -> text(c, cb, like(cb, root.get("title"), c));
            case "description" -> text(c, cb, like(cb, root.get("description"), c));
            case "comment" -> text(c, cb, commentLike(c, root, cb, query));
            case "due" -> dateField(c, cb, root.get("dueDate"), false);
            case "created" -> dateField(c, cb, root.get("createdAt"), true);
            case "updated" -> dateField(c, cb, root.get("updatedAt"), true);
            case "resolved" -> dateField(c, cb, root.get("completedAt"), true);
            case "points" -> numberField(c, cb, root.get("storyPoints"));
            case "parent" -> parent(c, root, cb);
            case "resolution" -> resolutionField(c, root, cb);
            case "component" -> component(c, root, cb, query);
            case "helper" -> helper(c, root, cb, query);
            case "archived" -> archived(c, root, cb);
            case "start" -> dateField(c, cb, root.get("startDate"), false);
            case "estimate" -> estimate(c, root, cb);
            default -> custom(c, root, cb, query);
        };
    }

    /** True when the query uses {@code field} anywhere. */
    public static boolean mentions(Node node, String field) {
        if (node == null) {
            return false;
        }
        if (node instanceof Fql.And and) {
            return mentions(and.left(), field) || mentions(and.right(), field);
        }
        if (node instanceof Fql.Or or) {
            return mentions(or.left(), field) || mentions(or.right(), field);
        }
        if (node instanceof Fql.Not not) {
            return mentions(not.inner(), field);
        }
        return node instanceof Clause clause && canonical(clause.field().toLowerCase(Locale.ROOT)).equals(field);
    }

    private com.fakejira.task.Resolution resolution(Value v) {
        String text = v.lower().replace("'", "").replace('-', ' ').replace('_', ' ').trim();
        return switch (text) {
            case "done" -> com.fakejira.task.Resolution.DONE;
            case "fixed" -> com.fakejira.task.Resolution.FIXED;
            case "wont do", "won t do", "wontdo", "wontfix", "wont fix" -> com.fakejira.task.Resolution.WONT_DO;
            case "duplicate" -> com.fakejira.task.Resolution.DUPLICATE;
            case "cannot reproduce", "cant reproduce", "cannotreproduce" -> com.fakejira.task.Resolution.CANNOT_REPRODUCE;
            default -> throw new FqlException("Unknown resolution '" + v.text()
                    + "'. Use done, fixed, \"won't do\", duplicate or \"cannot reproduce\".", v.position());
        };
    }

    private boolean bool(Value v) {
        return switch (v.lower()) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default -> throw new FqlException("Use true or false.", v.position());
        };
    }

    private Predicate resolutionField(Clause c, Root<Task> root, CriteriaBuilder cb) {
        Path<Object> path = root.get("resolution");
        // Tasks finished before resolutions existed count as "done".
        Expression<Object> effective = cb.selectCase()
                .when(cb.and(cb.isNull(path), cb.equal(root.get("status"), TaskStatus.DONE)), com.fakejira.task.Resolution.DONE)
                .otherwise(path);
        return switch (c.op()) {
            case EMPTY -> cb.notEqual(root.get("status"), TaskStatus.DONE);
            case NOT_EMPTY -> cb.equal(root.get("status"), TaskStatus.DONE);
            case EQ, IN -> effective.in(c.values().stream().map(this::resolution).toList());
            case NE, NOT_IN -> cb.or(cb.not(effective.in(c.values().stream().map(this::resolution).toList())),
                    cb.notEqual(root.get("status"), TaskStatus.DONE));
            default -> throw new FqlException("Use =, !=, IN or IS EMPTY with resolution.", c.position());
        };
    }

    private Predicate component(Clause c, Root<Task> root, CriteriaBuilder cb, jakarta.persistence.criteria.CriteriaQuery<?> query) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<Task> t = sub.from(Task.class);
        Join<Object, Object> component = t.join("components");
        Expression<String> name = cb.lower(component.get("name"));
        Predicate valueMatch = switch (c.op()) {
            case EMPTY, NOT_EMPTY -> cb.conjunction();
            case CONTAINS, NOT_CONTAINS -> cb.like(name, "%" + escape(c.values().get(0).lower()) + "%", '\\');
            default -> name.in(c.values().stream().map(Value::lower).toList());
        };
        sub.select(t.get("id")).where(cb.equal(t.get("id"), root.get("id")), valueMatch);
        Predicate exists = cb.exists(sub);
        return switch (c.op()) {
            case EQ, IN, NOT_EMPTY, CONTAINS -> exists;
            case NE, NOT_IN, EMPTY, NOT_CONTAINS -> cb.not(exists);
            default -> throw new FqlException("Use =, !=, IN, ~ or IS EMPTY with component.", c.position());
        };
    }

    private Predicate helper(Clause c, Root<Task> root, CriteriaBuilder cb, jakarta.persistence.criteria.CriteriaQuery<?> query) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<Task> t = sub.from(Task.class);
        Join<Task, User> h = t.join("helpers");
        List<Predicate> any = new ArrayList<>();
        if (c.op() != Op.EMPTY && c.op() != Op.NOT_EMPTY) {
            for (Value v : c.values()) {
                for (Object o : users(v)) {
                    any.add(o instanceof Long id ? cb.equal(h.get("id"), id) : cb.equal(cb.lower(h.get("username")), o));
                }
            }
        }
        sub.select(t.get("id")).where(cb.equal(t.get("id"), root.get("id")),
                any.isEmpty() ? cb.conjunction() : cb.or(any.toArray(Predicate[]::new)));
        Predicate exists = cb.exists(sub);
        return switch (c.op()) {
            case EQ, IN, NOT_EMPTY -> exists;
            case NE, NOT_IN, EMPTY -> cb.not(exists);
            default -> throw new FqlException("Use =, !=, IN or IS EMPTY with helper.", c.position());
        };
    }

    private Predicate archived(Clause c, Root<Task> root, CriteriaBuilder cb) {
        if (c.op() != Op.EQ && c.op() != Op.NE) {
            throw new FqlException("Use archived = true or archived = false.", c.position());
        }
        boolean wanted = bool(c.values().get(0)) == (c.op() == Op.EQ);
        return wanted ? cb.isNotNull(root.get("archivedAt")) : cb.isNull(root.get("archivedAt"));
    }

    /** Estimates are stored in minutes and queried in hours. */
    private Predicate estimate(Clause c, Root<Task> root, CriteriaBuilder cb) {
        Path<Integer> path = root.get("estimateMinutes");
        if (c.op() == Op.EMPTY || c.op() == Op.NOT_EMPTY) {
            return c.op() == Op.EMPTY ? cb.isNull(path) : cb.isNotNull(path);
        }
        int minutes = number(c.values().get(0)) * 60;
        return switch (c.op()) {
            case EQ -> cb.equal(path, minutes);
            case NE -> cb.or(cb.notEqual(path, minutes), cb.isNull(path));
            case GT -> cb.greaterThan(path, minutes);
            case GE -> cb.greaterThanOrEqualTo(path, minutes);
            case LT -> cb.lessThan(path, minutes);
            case LE -> cb.lessThanOrEqualTo(path, minutes);
            default -> throw new FqlException("Use =, !=, < or > with estimate.", c.position());
        };
    }

    private Predicate key(Clause c, Root<Task> root, CriteriaBuilder cb) {
        if (c.op() == Op.EMPTY || c.op() == Op.NOT_EMPTY) {
            return c.op() == Op.EMPTY ? cb.disjunction() : cb.conjunction();
        }
        List<Predicate> matches = new ArrayList<>();
        for (Value v : c.values()) {
            Matcher m = TASK_KEY.matcher(v.text().trim());
            if (!m.matches()) {
                throw new FqlException("'" + v.text() + "' is not a task key like WEB-12.", v.position());
            }
            matches.add(cb.and(cb.equal(root.get("project").get("key"), m.group(1).toUpperCase(Locale.ROOT)),
                    cb.equal(root.get("number"), Integer.valueOf(m.group(2)))));
        }
        Predicate any = cb.or(matches.toArray(Predicate[]::new));
        return switch (c.op()) {
            case EQ, IN -> any;
            case NE, NOT_IN -> cb.not(any);
            default -> throw new FqlException("Use =, != or IN with key.", c.position());
        };
    }

    /** Equality on a lower-cased name; {@code ~} for partial names; {@code nullable} enables EMPTY. */
    private Predicate named(Clause c, CriteriaBuilder cb, Expression<String> lowerName, Path<?> nullable) {
        switch (c.op()) {
            case EMPTY, NOT_EMPTY -> {
                if (nullable == null) {
                    return c.op() == Op.EMPTY ? cb.disjunction() : cb.conjunction();
                }
                return c.op() == Op.EMPTY ? cb.isNull(nullable) : cb.isNotNull(nullable);
            }
            case CONTAINS, NOT_CONTAINS -> {
                Predicate p = cb.like(lowerName, "%" + escape(c.values().get(0).lower()) + "%", '\\');
                return c.op() == Op.CONTAINS ? p : cb.or(cb.not(p), nullable == null ? cb.disjunction() : cb.isNull(nullable));
            }
            case EQ, IN -> {
                return lowerName.in(c.values().stream().map(Value::lower).toList());
            }
            case NE, NOT_IN -> {
                Predicate in = lowerName.in(c.values().stream().map(Value::lower).toList());
                return nullable == null ? cb.not(in) : cb.or(cb.not(in), cb.isNull(nullable));
            }
            default -> throw new FqlException("Use =, !=, ~, IN or IS EMPTY with " + c.field() + ".", c.position());
        }
    }

    private <E extends Enum<E>> Predicate enumIn(Clause c, CriteriaBuilder cb, Path<E> path, Set<E> values) {
        return switch (c.op()) {
            case EQ, IN, GT, GE, LT, LE -> path.in(values);
            case NE, NOT_IN -> cb.not(path.in(values));
            case EMPTY -> cb.disjunction();
            case NOT_EMPTY -> cb.conjunction();
            default -> throw new FqlException("Use =, !=, IN or comparisons with " + c.field() + ".", c.position());
        };
    }

    private Set<TaskStatus> statuses(Clause c) {
        List<TaskStatus> order = Arrays.asList(TaskStatus.values());
        return ordered(c, order, v -> switch (v.lower().replace('_', ' ').replace('-', ' ').trim()) {
            case "todo", "to do", "open", "new" -> TaskStatus.TODO;
            case "in progress", "inprogress", "progress", "doing", "started" -> TaskStatus.IN_PROGRESS;
            case "in review", "review", "inreview" -> TaskStatus.IN_REVIEW;
            case "done", "closed", "resolved", "finished" -> TaskStatus.DONE;
            default -> throw new FqlException("Unknown status '" + v.text() + "'. Use todo, \"in progress\", review or done.", v.position());
        });
    }

    private Set<TaskPriority> priorities(Clause c) {
        List<TaskPriority> order = Arrays.asList(TaskPriority.values());
        return ordered(c, order, v -> {
            try {
                return TaskPriority.valueOf(v.text().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new FqlException("Unknown priority '" + v.text() + "'. Use low, medium, high or critical.", v.position());
            }
        });
    }

    /** Values for =, IN; or every value on the requested side for comparisons (priority >= high). */
    private static <E extends Enum<E>> Set<E> ordered(Clause c, List<E> order, Function<Value, E> parse) {
        if (c.op() == Op.EMPTY || c.op() == Op.NOT_EMPTY) {
            return Set.of();
        }
        Set<E> picked = c.values().stream().map(parse).collect(Collectors.toSet());
        if (c.op() == Op.GT || c.op() == Op.GE || c.op() == Op.LT || c.op() == Op.LE) {
            E pivot = picked.iterator().next();
            Set<E> range = new java.util.HashSet<>();
            for (E e : order) {
                int cmp = Integer.compare(e.ordinal(), pivot.ordinal());
                if (c.op() == Op.GT && cmp > 0 || c.op() == Op.GE && cmp >= 0 || c.op() == Op.LT && cmp < 0
                        || c.op() == Op.LE && cmp <= 0) {
                    range.add(e);
                }
            }
            return range;
        }
        return picked;
    }

    private TaskType type(Value v) {
        try {
            return TaskType.valueOf(v.text().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FqlException("Unknown type '" + v.text() + "'. Use task, bug, story or spike.", v.position());
        }
    }

    /** Usernames or user ids a value stands for: a username, me, or membersOf(team). */
    private Collection<Object> users(Value v) {
        if (v.function()) {
            String name = v.lower();
            if (name.equals("me") || name.equals("currentuser")) {
                return List.of(user.getId());
            }
            if (name.equals("membersof")) {
                if (v.argument() == null) {
                    throw new FqlException("membersOf needs a team, e.g. membersOf(\"design\").", v.position());
                }
                Set<Long> ids = teamMembers.apply(v.argument());
                if (ids == null) {
                    throw new FqlException("There is no team called '" + v.argument() + "'.", v.position());
                }
                return new ArrayList<>(ids);
            }
            throw new FqlException("Unknown function " + v.text() + "(). Use me() or membersOf(team).", v.position());
        }
        if (v.lower().equals("me") || v.lower().equals("currentuser")) {
            return List.of(user.getId());
        }
        return List.of(v.lower());
    }

    private Predicate person(Clause c, CriteriaBuilder cb, Join<Task, User> person) {
        if (c.op() == Op.EMPTY) {
            return cb.isNull(person.get("id"));
        }
        if (c.op() == Op.NOT_EMPTY) {
            return cb.isNotNull(person.get("id"));
        }
        List<Predicate> any = new ArrayList<>();
        for (Value v : c.values()) {
            List<Long> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Object o : users(v)) {
                if (o instanceof Long id) {
                    ids.add(id);
                } else {
                    names.add((String) o);
                }
            }
            if (!ids.isEmpty()) {
                any.add(person.get("id").in(ids));
            }
            if (!names.isEmpty()) {
                any.add(cb.lower(person.get("username")).in(names));
            }
            if (ids.isEmpty() && names.isEmpty()) {
                any.add(cb.disjunction());
            }
        }
        Predicate match = cb.or(any.toArray(Predicate[]::new));
        return switch (c.op()) {
            case EQ, IN -> match;
            case NE, NOT_IN -> cb.or(cb.not(match), cb.isNull(person.get("id")));
            default -> throw new FqlException("Use =, !=, IN or IS EMPTY with " + c.field() + ".", c.position());
        };
    }

    private Predicate watcher(Clause c, Root<Task> root, CriteriaBuilder cb, jakarta.persistence.criteria.CriteriaQuery<?> query) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<Task> t = sub.from(Task.class);
        Join<Task, User> w = t.join("watchers");
        List<Predicate> any = new ArrayList<>();
        if (c.op() != Op.EMPTY && c.op() != Op.NOT_EMPTY) {
            for (Value v : c.values()) {
                for (Object o : users(v)) {
                    any.add(o instanceof Long id ? cb.equal(w.get("id"), id) : cb.equal(cb.lower(w.get("username")), o));
                }
            }
        }
        sub.select(t.get("id")).where(cb.equal(t.get("id"), root.get("id")),
                any.isEmpty() ? cb.conjunction() : cb.or(any.toArray(Predicate[]::new)));
        Predicate exists = cb.exists(sub);
        return switch (c.op()) {
            case EQ, IN, NOT_EMPTY -> exists;
            case NE, NOT_IN, EMPTY -> cb.not(exists);
            default -> throw new FqlException("Use =, != or IN with watcher.", c.position());
        };
    }

    private Predicate sprint(Clause c, Root<Task> root, CriteriaBuilder cb) {
        Join<Object, Object> sprint = root.join("sprint", JoinType.LEFT);
        if (c.op() == Op.EMPTY || c.op() == Op.NOT_EMPTY) {
            return c.op() == Op.EMPTY ? cb.isNull(sprint.get("id")) : cb.isNotNull(sprint.get("id"));
        }
        if (c.op() == Op.CONTAINS || c.op() == Op.NOT_CONTAINS) {
            return named(c, cb, cb.lower(sprint.get("name")), sprint.get("id"));
        }
        List<Predicate> any = new ArrayList<>();
        for (Value v : c.values()) {
            String text = v.lower();
            if (text.equals("active") || text.equals("activesprint") || text.equals("activesprints")) {
                any.add(cb.equal(sprint.get("state"), com.fakejira.sprint.SprintState.ACTIVE));
            } else if (text.equals("open") || text.equals("opensprints")) {
                any.add(sprint.get("state").in(com.fakejira.sprint.SprintState.ACTIVE, com.fakejira.sprint.SprintState.PLANNED));
            } else if (text.equals("closed") || text.equals("closedsprints") || text.equals("completed")) {
                any.add(cb.equal(sprint.get("state"), com.fakejira.sprint.SprintState.COMPLETED));
            } else if (text.matches("\\d+")) {
                any.add(cb.equal(sprint.get("id"), Long.valueOf(text)));
            } else {
                any.add(cb.equal(cb.lower(sprint.get("name")), text));
            }
        }
        Predicate match = cb.or(any.toArray(Predicate[]::new));
        return switch (c.op()) {
            case EQ, IN -> match;
            case NE, NOT_IN -> cb.or(cb.not(match), cb.isNull(sprint.get("id")));
            default -> throw new FqlException("Use =, !=, IN, ~ or IS EMPTY with sprint.", c.position());
        };
    }

    private Predicate release(Clause c, Root<Task> root, CriteriaBuilder cb) {
        Join<Object, Object> release = root.join("release", JoinType.LEFT);
        if ((c.op() == Op.EQ || c.op() == Op.NE) && c.values().get(0).lower().matches("unreleased(versions)?|released(versions)?")) {
            boolean released = c.values().get(0).lower().startsWith("released");
            Predicate p = cb.and(cb.isNotNull(release.get("id")), cb.equal(release.get("released"), released));
            return c.op() == Op.EQ ? p : cb.not(p);
        }
        return named(c, cb, cb.lower(release.get("name")), release.get("id"));
    }

    private Predicate label(Clause c, Root<Task> root, CriteriaBuilder cb, jakarta.persistence.criteria.CriteriaQuery<?> query) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<Task> t = sub.from(Task.class);
        Join<Task, String> label = t.join("labels");
        Predicate valueMatch;
        switch (c.op()) {
            case EMPTY, NOT_EMPTY -> valueMatch = cb.conjunction();
            case CONTAINS, NOT_CONTAINS -> valueMatch = cb.like(cb.lower(label), "%" + escape(c.values().get(0).lower()) + "%", '\\');
            default -> valueMatch = cb.lower(label).in(c.values().stream().map(Value::lower).toList());
        }
        sub.select(t.get("id")).where(cb.equal(t.get("id"), root.get("id")), valueMatch);
        Predicate exists = cb.exists(sub);
        return switch (c.op()) {
            case EQ, IN, NOT_EMPTY, CONTAINS -> exists;
            case NE, NOT_IN, EMPTY, NOT_CONTAINS -> cb.not(exists);
            default -> throw new FqlException("Use =, !=, IN, ~ or IS EMPTY with label.", c.position());
        };
    }

    private Predicate like(CriteriaBuilder cb, Path<String> path, Clause c) {
        List<Predicate> words = new ArrayList<>();
        for (String word : c.values().get(0).lower().trim().split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(cb.like(cb.lower(path), "%" + escape(word) + "%", '\\'));
            }
        }
        return words.isEmpty() ? cb.conjunction() : cb.and(words.toArray(Predicate[]::new));
    }

    private Predicate commentLike(Clause c, Root<Task> root, CriteriaBuilder cb, jakarta.persistence.criteria.CriteriaQuery<?> query) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<Comment> comment = sub.from(Comment.class);
        Predicate visible = internalProjects.isEmpty() ? cb.isFalse(comment.get("internal"))
                : cb.or(cb.isFalse(comment.get("internal")), root.get("project").get("id").in(internalProjects));
        sub.select(comment.get("id")).where(cb.equal(comment.get("task"), root), like(cb, comment.get("body"), c), visible);
        return cb.exists(sub);
    }

    private static Predicate text(Clause c, CriteriaBuilder cb, Predicate match) {
        return c.op() == Op.CONTAINS ? match : cb.not(match);
    }

    private Predicate parent(Clause c, Root<Task> root, CriteriaBuilder cb) {
        Join<Task, Task> parent = root.join("parent", JoinType.LEFT);
        if (c.op() == Op.EMPTY || c.op() == Op.NOT_EMPTY) {
            return c.op() == Op.EMPTY ? cb.isNull(parent.get("id")) : cb.isNotNull(parent.get("id"));
        }
        List<Predicate> any = new ArrayList<>();
        for (Value v : c.values()) {
            Matcher m = TASK_KEY.matcher(v.text().trim());
            if (!m.matches()) {
                throw new FqlException("'" + v.text() + "' is not a task key like WEB-12.", v.position());
            }
            any.add(cb.and(cb.equal(parent.get("project").get("key"), m.group(1).toUpperCase(Locale.ROOT)),
                    cb.equal(parent.get("number"), Integer.valueOf(m.group(2)))));
        }
        Predicate match = cb.or(any.toArray(Predicate[]::new));
        return c.op() == Op.EQ || c.op() == Op.IN ? match : cb.or(cb.not(match), cb.isNull(parent.get("id")));
    }

    /** A custom field's value (compared as text, case-insensitively). */
    private Predicate custom(Clause c, Root<Task> root, CriteriaBuilder cb, jakarta.persistence.criteria.CriteriaQuery<?> query) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<com.fakejira.field.CustomFieldValue> v = sub.from(com.fakejira.field.CustomFieldValue.class);
        Expression<String> value = cb.lower(v.get("value"));
        Predicate match = switch (c.op()) {
            case CONTAINS, NOT_CONTAINS -> cb.like(value, "%" + escape(c.values().get(0).lower()) + "%", '\\');
            case EMPTY, NOT_EMPTY -> cb.conjunction();
            default -> value.in(c.values().stream().map(Value::lower)
                    .map(x -> x.equals("yes") ? "true" : x).toList());
        };
        sub.select(v.get("id")).where(cb.equal(v.get("task"), root),
                cb.equal(cb.lower(v.get("field").get("name")), c.field().toLowerCase(Locale.ROOT)), match);
        Predicate exists = cb.exists(sub);
        return switch (c.op()) {
            case EQ, IN, CONTAINS, NOT_EMPTY -> exists;
            default -> cb.not(exists);
        };
    }

    private Predicate numberField(Clause c, CriteriaBuilder cb, Path<Integer> path) {
        return switch (c.op()) {
            case EMPTY -> cb.isNull(path);
            case NOT_EMPTY -> cb.isNotNull(path);
            case EQ -> cb.equal(path, number(c.values().get(0)));
            case NE -> cb.or(cb.notEqual(path, number(c.values().get(0))), cb.isNull(path));
            case GT -> cb.greaterThan(path, number(c.values().get(0)));
            case GE -> cb.greaterThanOrEqualTo(path, number(c.values().get(0)));
            case LT -> cb.lessThan(path, number(c.values().get(0)));
            case LE -> cb.lessThanOrEqualTo(path, number(c.values().get(0)));
            case IN -> path.in(c.values().stream().map(this::number).toList());
            case NOT_IN -> cb.or(cb.not(path.in(c.values().stream().map(this::number).toList())), cb.isNull(path));
            default -> throw new FqlException("~ does not work with numbers.", c.position());
        };
    }

    private Integer number(Value v) {
        try {
            return Integer.valueOf(v.text());
        } catch (NumberFormatException e) {
            throw new FqlException("'" + v.text() + "' is not a number.", v.position());
        }
    }

    /**
     * Dates compare by whole days. For timestamp fields "created = 2026-10-01" means during that day and
     * "created &lt; today" means before today started.
     */
    @SuppressWarnings("unchecked")
    private Predicate dateField(Clause c, CriteriaBuilder cb, Path<?> path, boolean instant) {
        if (c.op() == Op.EMPTY) {
            return cb.isNull(path);
        }
        if (c.op() == Op.NOT_EMPTY) {
            return cb.isNotNull(path);
        }
        if (c.op() == Op.IN || c.op() == Op.NOT_IN) {
            Predicate any = cb.or(c.values().stream().map(v -> day(cb, path, date(v), instant)).toArray(Predicate[]::new));
            return c.op() == Op.IN ? any : cb.or(cb.not(any), cb.isNull(path));
        }
        if (instant) {
            Instant exact = hours(c.values().get(0));
            if (exact != null) {
                Path<Instant> p = (Path<Instant>) path;
                return switch (c.op()) {
                    case GT -> cb.greaterThan(p, exact);
                    case GE, EQ -> cb.greaterThanOrEqualTo(p, exact);
                    case LT -> cb.lessThan(p, exact);
                    case LE -> cb.lessThanOrEqualTo(p, exact);
                    case NE -> cb.or(cb.lessThan(p, exact), cb.isNull(p));
                    default -> throw new FqlException("~ does not work with dates.", c.position());
                };
            }
        }
        LocalDate day = date(c.values().get(0));
        if (instant) {
            Path<Instant> p = (Path<Instant>) path;
            Instant start = day.atStartOfDay(zone).toInstant();
            Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
            return switch (c.op()) {
                case EQ -> day(cb, path, day, true);
                case NE -> cb.or(cb.not(day(cb, path, day, true)), cb.isNull(p));
                case GT -> cb.greaterThanOrEqualTo(p, end);
                case GE -> cb.greaterThanOrEqualTo(p, start);
                case LT -> cb.lessThan(p, start);
                case LE -> cb.lessThan(p, end);
                default -> throw new FqlException("~ does not work with dates.", c.position());
            };
        }
        Path<LocalDate> p = (Path<LocalDate>) path;
        return switch (c.op()) {
            case EQ -> cb.equal(p, day);
            case NE -> cb.or(cb.notEqual(p, day), cb.isNull(p));
            case GT -> cb.greaterThan(p, day);
            case GE -> cb.greaterThanOrEqualTo(p, day);
            case LT -> cb.lessThan(p, day);
            case LE -> cb.lessThanOrEqualTo(p, day);
            default -> throw new FqlException("~ does not work with dates.", c.position());
        };
    }

    @SuppressWarnings("unchecked")
    private Predicate day(CriteriaBuilder cb, Path<?> path, LocalDate day, boolean instant) {
        if (!instant) {
            return cb.equal(path, day);
        }
        Path<Instant> p = (Path<Instant>) path;
        return cb.and(cb.greaterThanOrEqualTo(p, day.atStartOfDay(zone).toInstant()),
                cb.lessThan(p, day.plusDays(1).atStartOfDay(zone).toInstant()));
    }

    private static final Pattern HOURS = Pattern.compile("^([+-]?)(\\d+)h$", Pattern.CASE_INSENSITIVE);

    /** A relative time in hours (-4h) for timestamp fields, else null. */
    static Instant hours(Value v) {
        Matcher m = HOURS.matcher(v.text());
        if (!m.matches()) {
            return null;
        }
        long amount = Long.parseLong(m.group(2)) * ("-".equals(m.group(1)) ? -1 : 1);
        return Instant.now().plus(java.time.Duration.ofHours(amount));
    }

    /** 2026-10-01, today, now, yesterday, tomorrow, -7d, +2w, 3m, startOfWeek, endOfMonth… */
    LocalDate date(Value v) {
        LocalDate today = LocalDate.now(zone);
        String text = v.lower().replace("()", "");
        switch (text) {
            case "today", "now" -> {
                return today;
            }
            case "yesterday" -> {
                return today.minusDays(1);
            }
            case "tomorrow" -> {
                return today.plusDays(1);
            }
            case "startofweek" -> {
                return today.with(DayOfWeek.MONDAY);
            }
            case "endofweek" -> {
                return today.with(DayOfWeek.SUNDAY);
            }
            case "startofmonth" -> {
                return today.withDayOfMonth(1);
            }
            case "endofmonth" -> {
                return today.withDayOfMonth(today.lengthOfMonth());
            }
            case "startofyear" -> {
                return today.withDayOfYear(1);
            }
            default -> {
            }
        }
        Matcher m = RELATIVE.matcher(text);
        if (m.matches()) {
            int amount = Integer.parseInt(m.group(2)) * ("-".equals(m.group(1)) ? -1 : 1);
            return switch (m.group(3).toLowerCase(Locale.ROOT)) {
                case "d" -> today.plusDays(amount);
                case "w" -> today.plusWeeks(amount);
                case "m" -> today.plusMonths(amount);
                default -> today.plusYears(amount);
            };
        }
        try {
            return LocalDate.parse(v.text());
        } catch (DateTimeParseException e) {
            throw new FqlException("'" + v.text() + "' is not a date. Use 2026-10-01, today, -7d or +2w.", v.position());
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Values that make sense after a field, for autocomplete. */
    public static List<String> staticValues(String field) {
        return switch (canonical(field)) {
            case "status" -> List.of("todo", "\"in progress\"", "review", "done");
            case "priority" -> List.of("low", "medium", "high", "critical");
            case "type" -> List.of("task", "bug", "story", "spike");
            case "assignee", "reporter", "watcher", "helper" -> List.of("me", "EMPTY", "membersOf(");
            case "resolution" -> List.of("done", "fixed", "\"won't do\"", "duplicate", "\"cannot reproduce\"", "EMPTY");
            case "archived" -> List.of("true", "false");
            case "component" -> List.of("EMPTY");
            case "sprint" -> List.of("active", "open", "closed", "EMPTY");
            case "release" -> List.of("unreleased", "EMPTY");
            case "due", "start", "created", "updated", "resolved" -> List.of("today", "-7d", "+7d", "startOfWeek", "startOfMonth", "EMPTY");
            default -> List.of();
        };
    }
}
