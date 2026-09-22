package com.aiinterview.interviewplatform.shared.id;

import java.util.UUID;

/**
 * The only sanctioned source of new entity identifiers.
 *
 * <p>Exists so that UUID generation appears in exactly one place. Business
 * code depends on this interface, never on {@code UUID.randomUUID()} or on a
 * database default — an ArchUnit rule fails the build on direct use.
 *
 * <p>{@code gen_random_uuid()} remains as a column DEFAULT in the schema, but
 * only as a safety net for manual and seed inserts.
 */
public interface IdGenerator {

    /** A new, time-ordered, non-guessable identifier. */
    UUID newId();
}
