/**
 * <strong>evaluation module.</strong>
 *
 * <p>Rubric-driven grading of a single answer. Deliberately does NOT depend on the interview module: it is handed an answer, a question version and a rubric, which is what makes it independently testable and reusable for the admin dry-run tool.
 *
 * <p>Boundary rule: other modules may depend only on this module's
 * {@code api} package, enforced by ArchitectureTest.
 */
package com.aiinterview.interviewplatform.evaluation;
