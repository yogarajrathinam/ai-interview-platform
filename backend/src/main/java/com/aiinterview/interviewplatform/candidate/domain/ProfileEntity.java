package com.aiinterview.interviewplatform.candidate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Candidate-supplied profile data.
 *
 * <p>Separate from {@code users} for two reasons: account deletion
 * hard-deletes this row while only anonymising the identity row, and the
 * authentication hot path stays narrow.
 *
 * <p>The shared primary key makes the 1:1 relationship structural rather than
 * conventional. Every field is optional by design — blocking signup on a form
 * costs conversion.
 */
@Entity
@Table(name = "profiles", schema = "app")
public class ProfileEntity {

    /** Shared PK: also the FK to {@code users.id} (ON DELETE CASCADE). */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "headline")
    private String headline;

    @Column(name = "years_experience")
    private Short yearsExperience;

    @Column(name = "target_role")
    private String targetRole;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ProfileEntity() {
        // for JPA
    }

    public UUID getUserId() { return userId; }
    public String getFullName() { return fullName; }
    public String getHeadline() { return headline; }
    public Short getYearsExperience() { return yearsExperience; }
    public String getTargetRole() { return targetRole; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
