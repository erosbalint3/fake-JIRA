package com.fakejira.planning;

import com.fakejira.common.ApiException;
import com.fakejira.common.CurrentUser;
import com.fakejira.events.LiveEvents;
import com.fakejira.task.Task;
import com.fakejira.task.TaskDeleting;
import com.fakejira.task.TaskSupport;
import com.fakejira.user.User;
import com.fakejira.user.UserRepository;
import com.fakejira.user.UserSummary;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Planning poker on a task: members vote privately, then reveal and accept an estimate. */
@RestController
public class PokerController {

    /** The deck: Fibonacci-ish points plus "?" (no idea) and "☕" (need a break). */
    public static final List<String> DECK = List.of("0", "1", "2", "3", "5", "8", "13", "21", "?", "☕");

    private final PokerRoundRepository rounds;
    private final TaskSupport taskSupport;
    private final UserRepository users;
    private final CurrentUser currentUser;
    private final LiveEvents live;

    public PokerController(PokerRoundRepository rounds, TaskSupport taskSupport, UserRepository users,
                           CurrentUser currentUser, LiveEvents live) {
        this.rounds = rounds;
        this.taskSupport = taskSupport;
        this.users = users;
        this.currentUser = currentUser;
        this.live = live;
    }

    public record Vote(UserSummary user, boolean voted, String value) {
    }

    /** {@code active} false means no round is running; {@code suggestion} is the deck card nearest the average. */
    public record PokerState(boolean active, boolean revealed, String startedBy, Instant startedAt, List<String> deck,
                             String myVote, List<Vote> votes, Double average, String suggestion, boolean consensus) {
    }

    public record VoteRequest(String value) {
    }

    public record AcceptRequest(Integer points) {
    }

    @GetMapping("/api/tasks/{id}/poker")
    @Transactional(readOnly = true)
    public PokerState state(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.memberTask(id, user);
        return state(rounds.findByTaskId(task.getId()).orElse(null), user);
    }

    /** Starts a round, or restarts the running one (clearing all votes). */
    @PostMapping("/api/tasks/{id}/poker")
    @Transactional
    public PokerState start(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        PokerRound round = rounds.findByTaskId(task.getId()).orElse(null);
        if (round == null) {
            round = rounds.save(new PokerRound(task.getId(), user.getUsername()));
            taskSupport.record(task, user, "started planning poker");
        } else {
            round.restart(user.getUsername());
        }
        live.taskChanged(task);
        return state(round, user);
    }

    @PutMapping("/api/tasks/{id}/poker/vote")
    @Transactional
    public PokerState vote(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @RequestBody VoteRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        PokerRound round = running(task);
        if (round.isRevealed()) {
            throw ApiException.badRequest("The votes are already revealed. Start a new round to vote again.");
        }
        if (request.value() == null) {
            round.getVotes().remove(user.getId());
        } else if (!DECK.contains(request.value())) {
            throw ApiException.badRequest("Pick one of the cards: " + String.join(" ", DECK) + ".");
        } else {
            round.getVotes().put(user.getId(), request.value());
        }
        live.taskChanged(task);
        return state(round, user);
    }

    @PostMapping("/api/tasks/{id}/poker/reveal")
    @Transactional
    public PokerState reveal(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        PokerRound round = running(task);
        if (round.getVotes().isEmpty()) {
            throw ApiException.badRequest("Nobody has voted yet.");
        }
        round.setRevealed(true);
        live.taskChanged(task);
        return state(round, user);
    }

    /** Sets the task's story points and ends the round. */
    @PostMapping("/api/tasks/{id}/poker/accept")
    @Transactional
    public PokerState accept(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                             @RequestBody AcceptRequest request) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        PokerRound round = running(task);
        if (request.points() == null || request.points() < 0 || request.points() > 100) {
            throw ApiException.badRequest("Story points must be between 0 and 100.");
        }
        task.setStoryPoints(request.points());
        taskSupport.record(task, user, "estimated the task at " + request.points() + " point"
                + (request.points() == 1 ? "" : "s") + " with planning poker (" + round.getVotes().size() + " vote"
                + (round.getVotes().size() == 1 ? "" : "s") + ")");
        rounds.delete(round);
        live.taskChanged(task);
        return state(null, user);
    }

    @DeleteMapping("/api/tasks/{id}/poker")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        User user = currentUser.from(jwt);
        Task task = taskSupport.editableTask(id, user);
        rounds.findByTaskId(task.getId()).ifPresent(rounds::delete);
        live.taskChanged(task);
    }

    @EventListener
    public void onTaskDeleting(TaskDeleting event) {
        rounds.findByTaskId(event.taskId()).ifPresent(rounds::delete);
    }

    private PokerRound running(Task task) {
        return rounds.findByTaskId(task.getId())
                .orElseThrow(() -> ApiException.badRequest("No planning poker round is running for this task."));
    }

    private PokerState state(PokerRound round, User user) {
        if (round == null) {
            return new PokerState(false, false, null, null, DECK, null, List.of(), null, null, false);
        }
        Map<Long, User> voters = new HashMap<>();
        users.findAllById(round.getVotes().keySet()).forEach(u -> voters.put(u.getId(), u));
        List<Vote> votes = round.getVotes().entrySet().stream()
                .filter(e -> voters.containsKey(e.getKey()))
                .map(e -> new Vote(UserSummary.of(voters.get(e.getKey())), true,
                        round.isRevealed() || e.getKey().equals(user.getId()) ? e.getValue() : null))
                .sorted(Comparator.comparing(v -> v.user().username()))
                .toList();
        Double average = null;
        String suggestion = null;
        boolean consensus = false;
        if (round.isRevealed()) {
            List<Integer> numbers = round.getVotes().values().stream().filter(v -> v.matches("\\d+"))
                    .map(Integer::parseInt).toList();
            if (!numbers.isEmpty()) {
                double avg = numbers.stream().mapToInt(Integer::intValue).average().orElse(0);
                average = Math.round(avg * 10) / 10.0;
                suggestion = nearestCard(avg);
                consensus = numbers.stream().distinct().count() == 1 && numbers.size() == round.getVotes().size();
            }
        }
        return new PokerState(true, round.isRevealed(), round.getStartedBy(), round.getStartedAt(), DECK,
                round.getVotes().get(user.getId()), votes, average, suggestion, consensus);
    }

    /** The smallest numeric card at or above the average (estimates round up). */
    static String nearestCard(double average) {
        return DECK.stream().filter(c -> c.matches("\\d+")).filter(c -> Integer.parseInt(c) >= average - 0.001)
                .findFirst().orElse("21");
    }
}
