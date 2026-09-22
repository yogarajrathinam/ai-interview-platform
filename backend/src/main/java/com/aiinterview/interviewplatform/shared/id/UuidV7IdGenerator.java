package com.aiinterview.interviewplatform.shared.id;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * UUIDv7 implementation of {@link IdGenerator}.
 *
 * <p>UUIDv7 embeds a Unix-millisecond timestamp in its high bits, so freshly
 * generated ids sort in creation order. That keeps B-tree inserts local
 * instead of scattering them across the index the way random UUIDv4 does,
 * which matters for the tables that grow fastest
 * ({@code evaluation_criterion_results}, {@code ai_invocations}).
 *
 * <p>Ids remain non-guessable: the random component is 74 bits, so exposing
 * an id in a URL leaks only an approximate creation time — and ids are never
 * treated as an access control in any case.
 *
 * <p>{@code Generators.timeBasedEpochGenerator()} is thread-safe.
 */
@Component
public class UuidV7IdGenerator implements IdGenerator {

    private final NoArgGenerator generator = Generators.timeBasedEpochGenerator();

    @Override
    public UUID newId() {
        return generator.generate();
    }
}
