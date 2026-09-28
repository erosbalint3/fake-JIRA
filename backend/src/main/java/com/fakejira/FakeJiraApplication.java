package com.fakejira;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FakeJiraApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakeJiraApplication.class, args);
    }
}
