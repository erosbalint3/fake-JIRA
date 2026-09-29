package com.fakejira.project;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ProjectTemplatesController {

    @GetMapping("/api/project-templates")
    public List<ProjectTemplates.Template> templates() {
        return ProjectTemplates.ALL;
    }
}
