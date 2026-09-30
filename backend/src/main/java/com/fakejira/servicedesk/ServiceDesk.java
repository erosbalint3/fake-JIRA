package com.fakejira.servicedesk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A project's public face: the request portal (and its embeddable widget), roadmap and changelog. */
@Entity
@Table(name = "service_desks")
public class ServiceDesk {

    public static final int MAX_INTRO = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    @Column(nullable = false)
    private boolean portalEnabled;

    /** Markdown shown at the top of the portal. */
    @Column(nullable = false, length = MAX_INTRO)
    private String intro = "";

    @Column(nullable = false)
    private boolean roadmapPublic;

    @Column(nullable = false)
    private boolean changelogPublic;

    protected ServiceDesk() {
    }

    public ServiceDesk(Long projectId) {
        this.projectId = projectId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public boolean isPortalEnabled() {
        return portalEnabled;
    }

    public String getIntro() {
        return intro;
    }

    public boolean isRoadmapPublic() {
        return roadmapPublic;
    }

    public boolean isChangelogPublic() {
        return changelogPublic;
    }

    public void update(boolean portalEnabled, String intro, boolean roadmapPublic, boolean changelogPublic) {
        this.portalEnabled = portalEnabled;
        this.intro = intro;
        this.roadmapPublic = roadmapPublic;
        this.changelogPublic = changelogPublic;
    }
}
