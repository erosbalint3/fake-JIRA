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
import jakarta.persistence.UniqueConstraint;

/** One person's emoji reaction to a comment. */
@Entity
@Table(name = "comment_reactions", uniqueConstraints = @UniqueConstraint(columnNames = {"comment_id", "user_id", "emoji"}))
public class CommentReaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comment_id", nullable = false)
    private Comment comment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 16)
    private String emoji;

    protected CommentReaction() {
    }

    public CommentReaction(Comment comment, User user, String emoji) {
        this.comment = comment;
        this.user = user;
        this.emoji = emoji;
    }

    public Comment getComment() {
        return comment;
    }

    public User getUser() {
        return user;
    }

    public String getEmoji() {
        return emoji;
    }
}
