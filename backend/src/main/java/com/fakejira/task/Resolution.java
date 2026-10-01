package com.fakejira.task;

/** Why a task was closed. Set when a task enters Done and cleared when it is reopened. */
public enum Resolution {
    DONE("Done"),
    FIXED("Fixed"),
    WONT_DO("Won't do"),
    DUPLICATE("Duplicate"),
    CANNOT_REPRODUCE("Cannot reproduce");

    private final String label;

    Resolution(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
