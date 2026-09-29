package com.fakejira.project;

import com.fakejira.automation.AutomationRule;
import com.fakejira.automation.AutomationRuleRepository;
import com.fakejira.board.BoardColumn;
import com.fakejira.board.BoardColumnRepository;
import com.fakejira.common.ApiException;
import com.fakejira.field.CustomField;
import com.fakejira.field.CustomFieldRepository;
import com.fakejira.task.TaskPriority;
import com.fakejira.task.TaskStatus;
import com.fakejira.task.TaskType;
import com.fakejira.template.TaskTemplate;
import com.fakejira.template.TaskTemplateRepository;
import com.fakejira.user.User;
import org.springframework.stereotype.Component;

import java.util.List;

/** Ready-made project setups: board columns, mode, custom fields, task templates and automation rules. */
@Component
public class ProjectTemplates {

    public record Template(String id, String name, String description) {
    }

    public static final List<Template> ALL = List.of(
            new Template("blank", "Blank", "Four columns and nothing else."),
            new Template("scrum", "Scrum software team", "Sprints, story and bug templates."),
            new Template("kanban", "Kanban", "Continuous flow with WIP limits: To do, Doing (3), Review (2), Done."),
            new Template("bugs", "Bug tracking", "Severity and environment fields, a bug report template and automatic triage."),
            new Template("marketing", "Content & marketing", "Ideas → Writing → Review → Published, with channel and publish date."));

    private final BoardColumnRepository columns;
    private final TaskTemplateRepository templates;
    private final CustomFieldRepository fields;
    private final AutomationRuleRepository rules;

    public ProjectTemplates(BoardColumnRepository columns, TaskTemplateRepository templates, CustomFieldRepository fields,
                            AutomationRuleRepository rules) {
        this.columns = columns;
        this.templates = templates;
        this.fields = fields;
        this.rules = rules;
    }

    public void apply(Project project, User user, String template) {
        if (template == null || template.isBlank() || template.equals("blank")) {
            return;
        }
        switch (template) {
            case "scrum" -> {
                storyTemplate(project);
                bugTemplate(project);
            }
            case "kanban" -> {
                project.setKanban(true);
                columns(project, new Col("To do", TaskStatus.TODO, null), new Col("Doing", TaskStatus.IN_PROGRESS, 3),
                        new Col("Review", TaskStatus.IN_REVIEW, 2), new Col("Done", TaskStatus.DONE, null));
            }
            case "bugs" -> {
                fields.save(new CustomField(project, "Severity", CustomField.Type.SELECT, List.of("Blocker", "Major", "Minor", "Trivial"), 0));
                fields.save(new CustomField(project, "Environment", CustomField.Type.TEXT, List.of(), 1));
                fields.save(new CustomField(project, "Affected version", CustomField.Type.TEXT, List.of(), 2));
                bugTemplate(project);
                rule(project, user, "Triage new bugs", "type = bug",
                        "[{\"type\":\"assign\",\"value\":\"least_loaded\"},{\"type\":\"add_label\",\"value\":\"triage\"}]");
                rule(project, user, "Escalate critical bugs untouched for 4 hours", "type = bug AND priority = critical AND status = todo AND updated < -4h",
                        "[{\"type\":\"add_label\",\"value\":\"sla-breach\"},{\"type\":\"notify\",\"value\":\"owner, assignee\"}]")
                        .setTrigger(AutomationRule.Trigger.SCHEDULED);
            }
            case "marketing" -> {
                project.setKanban(true);
                columns(project, new Col("Ideas", TaskStatus.TODO, null), new Col("Writing", TaskStatus.IN_PROGRESS, 4),
                        new Col("Review", TaskStatus.IN_REVIEW, null), new Col("Published", TaskStatus.DONE, null));
                fields.save(new CustomField(project, "Channel", CustomField.Type.SELECT, List.of("Blog", "Newsletter", "Social", "Video"), 0));
                fields.save(new CustomField(project, "Publish date", CustomField.Type.DATE, List.of(), 1));
                TaskTemplate post = new TaskTemplate(project);
                post.update("Blog post", TaskType.TASK, "Post: ", "**Audience:**\n\n**Key message:**\n\n**Call to action:**",
                        TaskPriority.MEDIUM, List.of("content"), List.of("Outline", "First draft", "Images", "Proofread", "Schedule"), null);
                templates.save(post);
            }
            default -> throw ApiException.field("template", "Unknown project template " + template + ".");
        }
    }

    private record Col(String name, TaskStatus status, Integer wip) {
    }

    private void columns(Project project, Col... list) {
        columns.deleteAll(columns.findByProjectIdOrderByPositionAscIdAsc(project.getId()));
        for (int i = 0; i < list.length; i++) {
            columns.save(new BoardColumn(project, list[i].name(), list[i].status(), i, list[i].wip()));
        }
    }

    private void storyTemplate(Project project) {
        TaskTemplate story = new TaskTemplate(project);
        story.update("User story", TaskType.STORY, "", "As a **…**\nI want **…**\nso that **…**\n\n### Acceptance criteria\n- ",
                TaskPriority.MEDIUM, List.of(), List.of("Acceptance criteria agreed", "Tests written", "Reviewed"), null);
        templates.save(story);
    }

    private void bugTemplate(Project project) {
        TaskTemplate bug = new TaskTemplate(project);
        bug.update("Bug report", TaskType.BUG, "Bug: ", "### Steps to reproduce\n1. \n\n### Expected\n\n### Actual\n",
                TaskPriority.HIGH, List.of("bug"), List.of("Reproduce", "Fix", "Add a test"), null);
        templates.save(bug);
    }

    private AutomationRule rule(Project project, User owner, String name, String condition, String actions) {
        AutomationRule rule = new AutomationRule(project, owner);
        rule.setName(name);
        rule.setTrigger(AutomationRule.Trigger.CREATED);
        rule.setCondition(condition);
        rule.setActions(actions);
        return rules.save(rule);
    }
}
