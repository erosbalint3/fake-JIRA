package com.fakejira.user;

public record UserSummary(Long id, String username, String email) {

    public static UserSummary of(User user) {
        return user == null ? null : new UserSummary(user.getId(), user.getUsername(), user.getEmail());
    }
}
