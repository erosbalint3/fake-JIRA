package com.example.fakejira.models;

import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Feladat {

    private static final AtomicInteger count = new AtomicInteger();
    private Integer taskID;
    private String taskName;
    private Integer taskPriority;
    private String taskDescription;

    public Feladat(String taskName, Integer taskPriority, String taskDescription) {
        this.taskID = count.incrementAndGet();
        this.taskName = taskName;
        this.taskPriority = taskPriority;
        this.taskDescription = taskDescription;
    }

    public Integer getTaskID() {
        return taskID;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Integer getTaskPriority() {
        return taskPriority;
    }

    public void setTaskPriority(Integer taskPriority) {
        this.taskPriority = taskPriority;
    }

    public String getTaskDescription() {
        return taskDescription;
    }

    public void setTaskDescription(String taskDescription) {
        this.taskDescription = taskDescription;
    }
}
