package com.aiinterview.interviewplatform.shared.error;

import org.springframework.http.HttpStatus;

/**
 * The stable machine-readable error contract.
 *
 * <p>Clients branch on {@code code}, never on {@code title} or on the HTTP
 * status alone. Adding a value is backwards compatible; renaming one is a
 * breaking API change.
 *
 * <p>The full set is defined here (it is part of the approved v1 contract)
 * even though M0/M1 raises only a few — this is the foundation the later
 * milestones plug into, not an invitation to invent new codes ad hoc.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Not permitted"),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email address not verified"),

    /** Also returned when a resource exists but belongs to someone else. */
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),

    INTERVIEW_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "Interview is not in progress"),
    LIVE_ATTEMPT_EXISTS(HttpStatus.CONFLICT, "A live interview attempt already exists"),
    VERSION_IMMUTABLE(HttpStatus.CONFLICT, "Published versions are immutable"),
    RESULT_NOT_AVAILABLE(HttpStatus.CONFLICT, "Result is not available"),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI provider unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Stable, dereferenceable problem type URI. */
    public String type() {
        return "https://api.aiinterview.dev/problems/" + name().toLowerCase().replace('_', '-');
    }
}
