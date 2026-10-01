package com.fakejira;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class FakeJiraApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FakeJiraApplication.class);
        // Applies a restore an admin scheduled, before the database opens.
        app.addListeners(new com.fakejira.ops.RestoreOnStartup());
        app.run(args);
    }
}
