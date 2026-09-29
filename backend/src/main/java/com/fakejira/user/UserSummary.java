package com.fakejira.user;

public record UserSummary(Long id, String username, String email, String displayName, String avatarUrl) {

    public static UserSummary of(User user) {
        if (user == null) {
            return null;
        }
        return new UserSummary(user.getId(), user.getUsername(), user.getEmail(), user.getName(),
                user.getAvatarName() == null ? null : "/api/avatars/" + user.getAvatarName());
    }
}
