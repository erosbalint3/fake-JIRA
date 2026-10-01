package com.fakejira.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Slash commands and link previews for a project in Slack, Mattermost and Discord. Secrets are write-only:
 * the API only says whether each one is set.
 */
@Entity
@Table(name = "chat_integrations")
public class ChatIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    /** Verifies Slack requests (Basic Information → Signing Secret). */
    @Column(length = 100)
    private String slackSigningSecret;

    /** Bot token (xoxb-…) used to unfurl task links; needs the links:write scope. */
    @Column(length = 200)
    private String slackBotToken;

    /** The token Mattermost sends with slash commands. */
    @Column(length = 100)
    private String mattermostToken;

    /** The Discord application's public key (hex), used to verify interactions. */
    @Column(length = 64)
    private String discordPublicKey;

    protected ChatIntegration() {
    }

    public ChatIntegration(Long projectId) {
        this.projectId = projectId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getSlackSigningSecret() {
        return slackSigningSecret;
    }

    public void setSlackSigningSecret(String slackSigningSecret) {
        this.slackSigningSecret = slackSigningSecret;
    }

    public String getSlackBotToken() {
        return slackBotToken;
    }

    public void setSlackBotToken(String slackBotToken) {
        this.slackBotToken = slackBotToken;
    }

    public String getMattermostToken() {
        return mattermostToken;
    }

    public void setMattermostToken(String mattermostToken) {
        this.mattermostToken = mattermostToken;
    }

    public String getDiscordPublicKey() {
        return discordPublicKey;
    }

    public void setDiscordPublicKey(String discordPublicKey) {
        this.discordPublicKey = discordPublicKey;
    }
}
