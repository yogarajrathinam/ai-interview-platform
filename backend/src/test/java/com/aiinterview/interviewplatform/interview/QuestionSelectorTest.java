package com.aiinterview.interviewplatform.interview;

import static org.assertj.core.api.Assertions.assertThat;

import com.aiinterview.interviewplatform.interview.application.QuestionSelector;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog.QuestionVersionView;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog.RubricCriterionView;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog.QuestionSlotView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Question selection policy.
 *
 * <p>Uses a stub catalogue rather than a database: the behaviour under test is
 * how a pool is <em>chosen from</em>, and that must hold for any pool the
 * catalogue happens to return, in any order.
 */
@DisplayName("Question selection")
class QuestionSelectorTest {

    private static final UUID JAVA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /** Returns exactly what a test puts in it, in insertion order. */
    private static final class StubCatalog implements QuestionCatalog {

        private final List<QuestionVersionView> pool = new ArrayList<>();
        private final List<QuestionVersionView> published = new ArrayList<>();

        StubCatalog withPool(QuestionVersionView... versions) {
            pool.addAll(List.of(versions));
            return this;
        }

        StubCatalog withPublished(QuestionVersionView... versions) {
            published.addAll(List.of(versions));
            return this;
        }

        @Override
        public Optional<QuestionVersionView> findVersion(UUID id) {
            return pool.stream().filter(v -> v.id().equals(id)).findFirst();
        }

        @Override
        public List<RubricCriterionView> findRubric(UUID questionVersionId) {
            return List.of();
        }

        @Override
        public Optional<QuestionVersionView> findPublishedVersionOfQuestion(UUID questionId) {
            return published.stream().filter(v -> v.questionId().equals(questionId)).findFirst();
        }

        @Override
        public List<QuestionVersionView> findPublishedVersionsForPool(
                UUID skillId, String difficulty, String questionType) {
            return List.copyOf(pool);
        }

        @Override
        public Set<UUID> findQuestionIdsForVersions(Collection<UUID> ids) {
            return Set.of();
        }
    }

    private static QuestionVersionView version(String label) {
        UUID questionId = UUID.nameUUIDFromBytes(("q-" + label).getBytes());
        UUID versionId = UUID.nameUUIDFromBytes(("v-" + label).getBytes());
        return new QuestionVersionView(versionId, questionId, 1, JAVA, "CONCEPTUAL", "MEDIUM",
                "Prompt " + label, null, "Reference", "PUBLISHED");
    }

    private static QuestionSlotView pooledSlot(int position) {
        return new QuestionSlotView(UUID.randomUUID(), position, null, JAVA,
                "MEDIUM", null, 3333);
    }

    private static QuestionSlotView pinnedSlot(int position, UUID questionId) {
        return new QuestionSlotView(UUID.randomUUID(), position, questionId, null,
                null, null, 3333);
    }

    // ---------------------------------------------------------- determinism

    @Test
    @DisplayName("the same interview always resolves the same question")
    void selectionIsDeterministic() {
        QuestionSelector selector = new QuestionSelector(new StubCatalog()
                .withPool(version("a"), version("b"), version("c"), version("d")));
        UUID interviewId = UUID.randomUUID();

        Optional<QuestionVersionView> first =
                selector.selectFor(pooledSlot(1), Set.of(), Set.of(), interviewId);
        Optional<QuestionVersionView> second =
                selector.selectFor(pooledSlot(1), Set.of(), Set.of(), interviewId);

        assertThat(first).isPresent().isEqualTo(second);
    }

    @Test
    @DisplayName("selection does not depend on the order the catalogue returns")
    void selectionIsIndependentOfCatalogueOrder() {
        UUID interviewId = UUID.randomUUID();
        QuestionSelector forward = new QuestionSelector(new StubCatalog()
                .withPool(version("a"), version("b"), version("c")));
        QuestionSelector reversed = new QuestionSelector(new StubCatalog()
                .withPool(version("c"), version("b"), version("a")));

        assertThat(forward.selectFor(pooledSlot(1), Set.of(), Set.of(), interviewId))
                .isEqualTo(reversed.selectFor(pooledSlot(1), Set.of(), Set.of(), interviewId));
    }

    @Test
    @DisplayName("different interviews do not all get the same question")
    void selectionVariesAcrossInterviews() {
        QuestionSelector selector = new QuestionSelector(new StubCatalog()
                .withPool(version("a"), version("b"), version("c"), version("d"),
                        version("e"), version("f")));

        Set<UUID> chosen = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            selector.selectFor(pooledSlot(1), Set.of(), Set.of(), UUID.randomUUID())
                    .ifPresent(v -> chosen.add(v.id()));
        }
        // A selector that always returned the first row would give every
        // candidate an identical interview.
        assertThat(chosen).hasSizeGreaterThan(1);
    }

    // ------------------------------------------------------------- repeats

    @Test
    @DisplayName("a question already in the plan is never chosen again")
    void noRepeatsWithinAPlan() {
        StubCatalog catalog = new StubCatalog().withPool(version("a"), version("b"));
        QuestionSelector selector = new QuestionSelector(catalog);
        UUID interviewId = UUID.randomUUID();

        QuestionVersionView first = selector
                .selectFor(pooledSlot(1), Set.of(), Set.of(), interviewId).orElseThrow();
        QuestionVersionView second = selector
                .selectFor(pooledSlot(2), Set.of(first.questionId()), Set.of(), interviewId)
                .orElseThrow();

        assertThat(second.questionId()).isNotEqualTo(first.questionId());
    }

    @Test
    @DisplayName("an exhausted pool yields nothing rather than a repeat")
    void exhaustedPoolReturnsEmpty() {
        QuestionSelector selector =
                new QuestionSelector(new StubCatalog().withPool(version("a")));
        QuestionVersionView only = version("a");

        // The planner turns this into a clear failure to start, which is better
        // than an interview that quietly asks the same question twice.
        assertThat(selector.selectFor(pooledSlot(2), Set.of(only.questionId()), Set.of(),
                UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("questions the candidate has seen before are avoided when possible")
    void previouslySeenQuestionsAreDeprioritised() {
        QuestionVersionView seen = version("seen");
        QuestionVersionView fresh = version("fresh");
        QuestionSelector selector =
                new QuestionSelector(new StubCatalog().withPool(seen, fresh));

        Optional<QuestionVersionView> chosen = selector.selectFor(
                pooledSlot(1), Set.of(), Set.of(seen.questionId()), UUID.randomUUID());

        assertThat(chosen).contains(fresh);
    }

    @Test
    @DisplayName("a repeat is preferred over refusing to start")
    void exposureAvoidanceIsAPreferenceNotAConstraint() {
        QuestionVersionView seen = version("seen");
        QuestionSelector selector =
                new QuestionSelector(new StubCatalog().withPool(seen));

        // With a small bank, refusing to repeat would mean refusing to run an
        // interview at all — much worse than a familiar question.
        assertThat(selector.selectFor(pooledSlot(1), Set.of(), Set.of(seen.questionId()),
                UUID.randomUUID())).contains(seen);
    }

    // -------------------------------------------------------- pinned slots

    @Test
    @DisplayName("a pinned slot resolves to that question's published version")
    void pinnedSlotResolvesItsOwnQuestion() {
        QuestionVersionView pinned = version("pinned");
        QuestionSelector selector =
                new QuestionSelector(new StubCatalog().withPublished(pinned));

        assertThat(selector.selectFor(pinnedSlot(1, pinned.questionId()), Set.of(), Set.of(),
                UUID.randomUUID())).contains(pinned);
    }

    @Test
    @DisplayName("a pinned question with no published version yields nothing")
    void pinnedSlotWithoutPublishedVersion() {
        QuestionSelector selector = new QuestionSelector(new StubCatalog());

        assertThat(selector.selectFor(pinnedSlot(1, UUID.randomUUID()), Set.of(), Set.of(),
                UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a pinned question already in the plan is not duplicated")
    void pinnedSlotRespectsWithinPlanUniqueness() {
        QuestionVersionView pinned = version("pinned");
        QuestionSelector selector =
                new QuestionSelector(new StubCatalog().withPublished(pinned));

        assertThat(selector.selectFor(pinnedSlot(2, pinned.questionId()),
                Set.of(pinned.questionId()), Set.of(), UUID.randomUUID())).isEmpty();
    }
}
