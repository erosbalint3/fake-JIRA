package com.fakejira.task;

/** What kind of work a task is; shown as an icon and filterable. */
public enum TaskType {
    TASK("Task"),
    BUG("Bug"),
    STORY("Story"),
    SPIKE("Spike");

    private final String label;

    TaskType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
