package com.aiinterview.interviewplatform.shared.error;

/**
 * One field-level validation failure, rendered in the {@code errors} array of
 * a problem response.
 *
 * <p>{@code code} is machine readable ({@code TOO_SHORT}, {@code NOT_NULL});
 * {@code message} is for display.
 */
public record FieldViolation(String field, String code, String message) {}
