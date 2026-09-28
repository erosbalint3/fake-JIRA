package com.fakejira.task;

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

@Entity
@Table(name = "attachments")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_id", nullable = false)
    private User uploader;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(nullable = false, length = 150)
    private String contentType;

    @Column(nullable = false)
    private long size;

    /** Random name of the file on disk; never derived from user input. */
    @Column(nullable = false, unique = true, length = 64)
    private String storageName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Attachment() {
    }

    public Attachment(Task task, User uploader, String filename, String contentType, long size, String storageName) {
        this.task = task;
        this.uploader = uploader;
        this.filename = filename;
        this.contentType = contentType;
        this.size = size;
        this.storageName = storageName;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public User getUploader() {
        return uploader;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    public String getStorageName() {
        return storageName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
