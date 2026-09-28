package com.fakejira.user;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    public record UserMatch(Long id, String username) {
    }

    /** Username autocomplete for adding project members; needs at least 2 characters. */
    @GetMapping
    public List<UserMatch> search(@RequestParam String q) {
        String query = q.trim();
        if (query.length() < 2) {
            return List.of();
        }
        return users.findByUsernameContainingIgnoreCaseOrderByUsername(query, PageRequest.of(0, 8)).stream()
                .map(user -> new UserMatch(user.getId(), user.getUsername()))
                .toList();
    }
}
