package com.fakejira.integration;

import com.fakejira.task.Task;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** A task imported from (and kept in sync with) a GitHub issue. */
@Entity
@Table(name = "issue_links", uniqueConstraints = @UniqueConstraint(columnNames = {"repo", "number"}))
public class IssueLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, unique = true)
    private Task task;

    @Column(nullable = false, length = 140)
    private String repo;

    @Column(nullable = false)
    private int number;

    @Column(nullable = false, length = 500)
    private String url;

    protected IssueLink() {
    }

    public IssueLink(Task task, String repo, int number, String url) {
        this.task = task;
        this.repo = repo;
        this.number = number;
        this.url = url;
    }

    public Task getTask() {
        return task;
    }

    public String getRepo() {
        return repo;
    }

    public int getNumber() {
        return number;
    }

    public String getUrl() {
        return url;
    }
}
