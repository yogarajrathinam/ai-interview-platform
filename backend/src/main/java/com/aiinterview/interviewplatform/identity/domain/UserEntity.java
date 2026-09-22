package com.aiinterview.interviewplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Authoritative identity inside our system, decoupled from the identity
 * provider.
 *
 * <p>{@code authSubject} is an opaque {@code sub} from Supabase and is
 * deliberately <em>not</em> a foreign key into any provider-managed schema,
 * so the platform stays portable.
 *
 * <p>{@code role} is the authoritative role. It is never read from a JWT
 * claim (docs/08-security.md §4).
 */
@Entity
@Table(name = "users", schema = "app")
public class UserEntity {

    public enum Role { CANDIDATE, ADMIN }

    public enum Status { ACTIVE, SUSPENDED, DELETED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "auth_subject", nullable = false)
    private String authSubject;

    @Column(name = "auth_provider", nullable = false)
    private String authProvider;

    /**
     * Stored as {@code text}; uniqueness is enforced case-insensitively by the
     * functional index {@code uq_users_email on lower(email)}.
     */
    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "display_name")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    // Attribution for the two security-relevant mutations. These replace the
    // generic audit_log table removed in review (docs/10 §8).
    @Column(name = "role_changed_at")
    private OffsetDateTime roleChangedAt;

    @Column(name = "role_changed_by")
    private UUID roleChangedBy;

    @Column(name = "status_changed_at")
    private OffsetDateTime statusChangedAt;

    @Column(name = "status_changed_by")
    private UUID statusChangedBy;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserEntity() {
        // for JPA
    }

    public UUID getId() { return id; }
    public String getAuthSubject() { return authSubject; }
    public String getAuthProvider() { return authProvider; }
    public String getEmail() { return email; }
    public boolean isEmailVerified() { return emailVerified; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }
    public OffsetDateTime getRoleChangedAt() { return roleChangedAt; }
    public UUID getRoleChangedBy() { return roleChangedBy; }
    public OffsetDateTime getStatusChangedAt() { return statusChangedAt; }
    public UUID getStatusChangedBy() { return statusChangedBy; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
