/**
 * <strong>shared module.</strong>
 *
 * <p>Cross-cutting mechanics: error handling, tracing, clock, id generation, configuration, security wiring and the job queue. Contains NO business rules. Every module may depend on this one; it may depend on none of them.
 *
 * <p>Boundary rule: other modules may depend only on this module's
 * {@code api} package, enforced by ArchitectureTest.
 */
package com.aiinterview.interviewplatform.shared;
