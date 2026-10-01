package com.fakejira.servicedesk;

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

/**
 * A message in the conversation with the requester. Separate from task comments, which stay internal:
 * the requester only ever sees these.
 */
@Entity
@Table(name = "portal_messages")
public class PortalMessage {

    public static final int MAX_BODY = 5000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private PortalRequest request;

    /** The team member who replied; null when the requester wrote it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(nullable = false, length = MAX_BODY)
    private String body;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PortalMessage() {
    }

    public PortalMessage(PortalRequest request, User author, String body) {
        this.request = request;
        this.author = author;
        this.body = body;
    }

    public Long getId() {
        return id;
    }

    public PortalRequest getRequest() {
        return request;
    }

    public User getAuthor() {
        return author;
    }

    public boolean isFromRequester() {
        return author == null;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
