package com.aiinterview.interviewplatform.support.golden;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Loads the labelled dataset from the classpath.
 *
 * <p>Cases are sorted by id so a run is reproducible and two runs are
 * diff-comparable. Loading fails loudly on an unknown or missing field rather
 * than defaulting it: a typo in a case file must not silently drop a label and
 * shrink the denominator the agreement score is measured against.
 */
public final class GoldenSet {

    private static final String PATTERN = "classpath*:golden/cases/*.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private GoldenSet() {
    }

    public static List<GoldenCase> load() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not scan " + PATTERN, e);
        }

        if (resources.length == 0) {
            throw new IllegalStateException(
                    "The golden set is empty. " + PATTERN + " matched nothing, which would let "
                            + "the agreement gate pass vacuously.");
        }

        List<GoldenCase> cases = new ArrayList<>(resources.length);
        for (Resource resource : resources) {
            try {
                cases.add(MAPPER.readValue(resource.getInputStream(), GoldenCase.class));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Malformed golden case: " + resource.getFilename(), e);
            }
        }
        cases.sort(Comparator.comparing(GoldenCase::id));
        return List.copyOf(cases);
    }
}
