package com.aiinterview.interviewplatform.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the single {@link Clock} the application must use.
 *
 * <p>Business code never calls {@code Instant.now()} or
 * {@code LocalDateTime.now()} directly — an ArchUnit rule fails the build if
 * it does. Interview deadlines, the follow-up phase and the maintenance
 * sweeper are all time-driven, and they are only testable if time is
 * injectable.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
