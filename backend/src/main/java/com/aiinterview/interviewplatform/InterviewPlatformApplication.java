package com.aiinterview.interviewplatform;

import com.aiinterview.interviewplatform.shared.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Phase 0 modular monolith.
 *
 * <p>Module boundaries are packages under this root and are enforced by the
 * ArchUnit suite, not by convention. See {@code docs/architecture.md}.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class InterviewPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewPlatformApplication.class, args);
    }
}
