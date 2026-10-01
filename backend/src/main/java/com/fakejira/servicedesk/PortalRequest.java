package com.fakejira.servicedesk;

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

import java.time.Instant;

/** Who asked for a task through the portal, their answers, and the secret token of their tracking page. */
@Entity
@Table(name = "portal_requests")
public class PortalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, unique = true)
    private Task task;

    @Column(nullable = false, length = 80)
    private String requesterName;

    @Column(nullable = false, length = 254)
    private String requesterEmail;

    @Column(nullable = false, unique = true, length = 48)
    private String token;

    /** Name of the request type at the time (types can be renamed or deleted later). */
    @Column(length = 80)
    private String requestType;

    /** Answers to the form's extra fields as JSON: [{label, value}]. */
    @Column(nullable = false, length = 10000)
    private String answers = "[]";

    /** "portal" or "widget". */
    @Column(nullable = false, length = 10)
    private String channel = "portal";

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PortalRequest() {
    }

    public PortalRequest(Task task, String requesterName, String requesterEmail, String token, String requestType,
                         String answers, String channel) {
        this.task = task;
        this.requesterName = requesterName;
        this.requesterEmail = requesterEmail;
        this.token = token;
        this.requestType = requestType;
        this.answers = answers;
        this.channel = channel;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public String getRequesterName() {
        return requesterName;
    }

    public String getRequesterEmail() {
        return requesterEmail;
    }

    public String getToken() {
        return token;
    }

    public String getRequestType() {
        return requestType;
    }

    public String getAnswers() {
        return answers;
    }

    public String getChannel() {
        return channel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
