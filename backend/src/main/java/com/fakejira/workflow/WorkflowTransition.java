package com.fakejira.workflow;

import com.fakejira.board.BoardColumn;
import com.fakejira.project.Project;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** An allowed move between two board columns; a null {@code from} means "from any column". */
@Entity
@Table(name = "workflow_transitions")
public class WorkflowTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_column_id")
    private BoardColumn from;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_column_id", nullable = false)
    private BoardColumn to;

    protected WorkflowTransition() {
    }

    public WorkflowTransition(Project project, BoardColumn from, BoardColumn to) {
        this.project = project;
        this.from = from;
        this.to = to;
    }

    public Long getId() {
        return id;
    }

    public BoardColumn getFrom() {
        return from;
    }

    public BoardColumn getTo() {
        return to;
    }
}
