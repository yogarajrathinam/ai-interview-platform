/**
 * <strong>ai module.</strong>
 *
 * <p>The AI gateway. The only code permitted to talk to a model provider. Provider SDK types never leave this module's infrastructure package, so swapping providers is a new adapter rather than a rewrite.
 *
 * <p>Boundary rule: other modules may depend only on this module's
 * {@code api} package, enforced by ArchitectureTest.
 */
package com.aiinterview.interviewplatform.ai;
