package com.fakejira.wiki;

import com.fakejira.project.Project;
import com.fakejira.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** A Markdown page in a project's wiki; pages nest under a parent page. */
@Entity
@Table(name = "wiki_pages")
public class WikiPage {

    public static final int MAX_BODY = 100_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private WikiPage parent;

    @Column(nullable = false, length = 120)
    private String title;

    /** URL name, unique within the project. */
    @Column(nullable = false, length = 90)
    private String slug;

    @Column(nullable = false, length = MAX_BODY)
    private String body = "";

    @Column(nullable = false)
    private int version = 1;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "updated_by_id", nullable = false)
    private User updatedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected WikiPage() {
    }

    public WikiPage(Project project, String title, String slug, String body, User author) {
        this.project = project;
        this.title = title;
        this.slug = slug;
        this.body = body;
        this.createdBy = author;
        this.updatedBy = author;
    }

    /** Saves new content as the next version. */
    public void revise(String title, String body, User author) {
        this.title = title;
        this.body = body;
        this.updatedBy = author;
        this.updatedAt = Instant.now();
        this.version++;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public WikiPage getParent() {
        return parent;
    }

    public void setParent(WikiPage parent) {
        this.parent = parent;
    }

    public String getTitle() {
        return title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getBody() {
        return body;
    }

    public int getVersion() {
        return version;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public User getUpdatedBy() {
        return updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
