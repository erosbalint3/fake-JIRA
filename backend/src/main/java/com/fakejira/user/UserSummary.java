package com.fakejira.user;

/** {@code awayUntil}: the last day of the person's out-of-office, set only while they are away today. */
public record UserSummary(Long id, String username, String email, String displayName, String avatarUrl,
                          java.time.LocalDate awayUntil) {

    public static UserSummary of(User user) {
        if (user == null) {
            return null;
        }
        return new UserSummary(user.getId(), user.getUsername(), GuestPrivacy.hideEmailOf(user.getId()) ? null : user.getEmail(), user.getName(),
                user.getAvatarName() == null ? null : "/api/avatars/" + user.getAvatarName(),
                user.isAwayOn(java.time.LocalDate.now()) ? user.getAwayUntil() : null);
    }
}
