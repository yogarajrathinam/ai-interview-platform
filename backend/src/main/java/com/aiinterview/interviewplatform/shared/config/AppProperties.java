package com.aiinterview.interviewplatform.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly typed application configuration.
 *
 * <p>Validated at startup: a missing or malformed value fails the context
 * refresh immediately rather than surfacing as a {@code null} in production.
 * Nothing secret belongs here — secrets arrive as environment variables and
 * are consumed by Spring Boot's own datasource/security configuration.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(

        /** Deployment environment name, used in logs and diagnostics. */
        @NotBlank
        @Pattern(regexp = "local|test|staging|prod",
                 message = "must be one of: local, test, staging, prod")
        String environment,

        /**
         * Build identifier of the interview engine, stamped onto every
         * interview attempt for forensics ({@code interviews.engine_version}).
         */
        @NotBlank
        String engineVersion,

        @Valid
        @DefaultValue
        Api api
) {

    public record Api(
            @NotBlank
            @Pattern(regexp = "/api/v\\d+", message = "must look like /api/v1")
            @DefaultValue("/api/v1")
            String basePath
    ) {}
}
