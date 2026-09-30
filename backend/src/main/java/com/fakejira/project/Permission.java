package com.fakejira.project;

/**
 * What a custom project role may do. Members without a custom role can do all of it; viewers and guests none
 * (guests may still comment); the owner always everything.
 */
public enum Permission {
    CREATE_TASKS("Create tasks"),
    EDIT_TASKS("Edit and move tasks"),
    DELETE_TASKS("Delete tasks they reported"),
    COMMENT("Comment"),
    LOG_TIME("Log time"),
    MANAGE_SPRINTS("Plan and run sprints"),
    MANAGE_RELEASES("Manage releases"),
    MANAGE_EPICS("Manage epics"),
    VIEW_INTERNAL("See internal comments");

    private final String label;

    Permission(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
