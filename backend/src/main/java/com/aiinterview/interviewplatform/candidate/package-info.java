/**
 * <strong>candidate module.</strong>
 *
 * <p>Candidate-supplied profile data. Owns {@code profiles}. Separate from identity so account deletion can hard-delete the profile while only anonymising the identity row.
 *
 * <p>Boundary rule: other modules may depend only on this module's
 * {@code api} package, enforced by ArchitectureTest.
 */
package com.aiinterview.interviewplatform.candidate;
