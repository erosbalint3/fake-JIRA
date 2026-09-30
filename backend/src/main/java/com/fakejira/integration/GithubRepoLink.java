package com.fakejira.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The GitHub repository a project works in, with a token for creating branches and PRs and syncing issues. */
@Entity
@Table(name = "github_repo_links")
public class GithubRepoLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    /** owner/name */
    @Column(nullable = false, length = 140)
    private String repo;

    /** A fine-grained personal access token; never sent back to the browser. */
    @Column(length = 300)
    private String token;

    @Column(length = 100)
    private String defaultBranch;

    /** Keep GitHub issues and their tasks in sync (both ways). */
    @Column(nullable = false)
    private boolean issueSync;

    protected GithubRepoLink() {
    }

    public GithubRepoLink(Long projectId) {
        this.projectId = projectId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getRepo() {
        return repo;
    }

    public String getToken() {
        return token;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public boolean isIssueSync() {
        return issueSync;
    }

    public void update(String repo, String token, String defaultBranch, boolean issueSync) {
        this.repo = repo;
        this.token = token;
        this.defaultBranch = defaultBranch;
        this.issueSync = issueSync;
    }
}
