package com.fakejira.project;

import com.fakejira.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short uppercase prefix used in task keys, e.g. WEB in WEB-12. Immutable. */
    @Column(name = "project_key", nullable = false, unique = true, length = 10)
    private String key;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description = "";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToMany
    @JoinTable(name = "project_members",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> members = new LinkedHashSet<>();

    /** Members with read-only access (a subset of {@link #members}). */
    @ManyToMany
    @JoinTable(name = "project_viewers",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<User> viewers = new LinkedHashSet<>();

    /** Secret for verifying GitHub webhook signatures; null when the integration is off. */
    @Column(length = 64)
    private String githubSecret;

    /** Move linked tasks to Done when a pull request mentioning them is merged. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean githubAutoDone;

    /** Next task number to hand out within this project. */
    @Column(nullable = false)
    private int nextNumber = 1;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Project() {
    }

    public Project(String key, String name, String description, User owner) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.members.add(owner);
    }

    public int allocateNumber() {
        return nextNumber++;
    }

    public boolean isOwner(User user) {
        return owner.getId().equals(user.getId());
    }

    public boolean hasMember(User user) {
        return members.stream().anyMatch(member -> member.getId().equals(user.getId()));
    }

    public boolean isViewer(User user) {
        return viewers.stream().anyMatch(viewer -> viewer.getId().equals(user.getId()));
    }

    /** Members who are not read-only viewers can change things. */
    public boolean canEdit(User user) {
        return hasMember(user) && !isViewer(user);
    }

    public Long getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public User getOwner() {
        return owner;
    }

    public Set<User> getMembers() {
        return members;
    }

    public Set<User> getViewers() {
        return viewers;
    }

    public String getGithubSecret() {
        return githubSecret;
    }

    public void setGithubSecret(String githubSecret) {
        this.githubSecret = githubSecret;
    }

    public boolean isGithubAutoDone() {
        return githubAutoDone;
    }

    public void setGithubAutoDone(boolean githubAutoDone) {
        this.githubAutoDone = githubAutoDone;
    }

    public int getNextNumber() {
        return nextNumber;
    }

    public void setNextNumber(int nextNumber) {
        this.nextNumber = nextNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
