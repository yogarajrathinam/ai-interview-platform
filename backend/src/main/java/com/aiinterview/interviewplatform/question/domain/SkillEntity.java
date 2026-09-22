package com.aiinterview.interviewplatform.question.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The taxonomy scores are grouped by.
 *
 * <p>Skills are <em>data</em>, never a code enum: adding a domain (React,
 * System Design, and later non-engineering subjects) must not require a
 * deploy. Seeded by {@code V2__seed_skills.sql}.
 */
@Entity
@Table(name = "skills", schema = "app")
public class SkillEntity {

    public enum Status { ACTIVE, ARCHIVED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Stable business key, e.g. {@code JAVA}. Unique. */
    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "sort_order", nullable = false)
    private Short sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SkillEntity() {
        // for JPA
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public Short getSortOrder() { return sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
