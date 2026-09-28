package com.fakejira.task;

public enum TaskStatus {
    TODO("To do"),
    IN_PROGRESS("In progress"),
    IN_REVIEW("In review"),
    DONE("Done");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
