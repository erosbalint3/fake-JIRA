package com.fakejira.user;

/**
 * Request-scoped flag: while a project guest (e.g. an outside client) is making the request, other people's
 * email addresses are left out of every user summary.
 */
public final class GuestPrivacy {

    private static final ThreadLocal<Long> GUEST_VIEWER = new ThreadLocal<>();

    private GuestPrivacy() {
    }

    public static void set(Long guestUserId) {
        GUEST_VIEWER.set(guestUserId);
    }

    public static void clear() {
        GUEST_VIEWER.remove();
    }

    /** Whether {@code userId}'s email must be hidden from whoever is making the current request. */
    public static boolean hideEmailOf(Long userId) {
        Long viewer = GUEST_VIEWER.get();
        return viewer != null && !viewer.equals(userId);
    }
}
