/**
 * <strong>identity module.</strong>
 *
 * <p>Who the user is: the local user record, role and status. Owns {@code users}. The external identity provider is held at arm's length behind an opaque auth subject, so the platform stays portable.
 *
 * <p>Boundary rule: other modules may depend only on this module's
 * {@code api} package, enforced by ArchitectureTest.
 */
package com.aiinterview.interviewplatform.identity;
