/**
 * <strong>admin module.</strong>
 *
 * <p>Cross-module read models for operators: the overview counts and job-queue visibility. Content administration lives with the module that owns the data, so this module stays small and does not become a second application.
 *
 * <p>Boundary rule: other modules may depend only on this module's
 * {@code api} package, enforced by ArchitectureTest.
 */
package com.aiinterview.interviewplatform.admin;
