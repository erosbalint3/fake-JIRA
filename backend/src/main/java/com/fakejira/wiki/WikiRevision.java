package com.fakejira.wiki;

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

/** A saved version of a wiki page (the page itself holds the newest). */
@Entity
@Table(name = "wiki_revisions")
public class WikiRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "page_id", nullable = false)
    private WikiPage page;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = WikiPage.MAX_BODY)
    private String body;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false)
    private Instant createdAt;

    protected WikiRevision() {
    }

    public WikiRevision(WikiPage page) {
        this.page = page;
        this.version = page.getVersion();
        this.title = page.getTitle();
        this.body = page.getBody();
        this.author = page.getUpdatedBy();
        this.createdAt = page.getUpdatedAt();
    }

    public Long getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public User getAuthor() {
        return author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
