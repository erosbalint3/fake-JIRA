package com.fakejira.project;

import com.fakejira.common.ApiException;

/** Validation for the emoji people pick for projects and epics. */
public final class Icons {

    private Icons() {
    }

    /** Null for "no icon"; otherwise one short emoji or symbol (no letters, digits or markup). */
    public static String clean(String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        String value = icon.strip();
        if (value.codePointCount(0, value.length()) > 8 || value.length() > 16
                || value.codePoints().anyMatch(c -> Character.isLetterOrDigit(c) && c < 0x2100
                || Character.isWhitespace(c) || Character.isISOControl(c) || "<>&\"'`".indexOf(c) >= 0)) {
            throw ApiException.field("icon", "Pick one emoji.");
        }
        return value;
    }
}
