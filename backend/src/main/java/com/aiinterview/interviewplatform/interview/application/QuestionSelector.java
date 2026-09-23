package com.aiinterview.interviewplatform.interview.application;

import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog.QuestionVersionView;
import com.aiinterview.interviewplatform.question.api.TemplateCatalog.QuestionSlotView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Chooses which published question version fills a template slot.
 *
 * <p>Selection <em>policy</em> lives here, in the interview module, while the
 * eligible set comes from the question catalogue. The split matters: only this
 * module knows the candidate, what they have already seen, and what the rest of
 * the plan already contains.
 *
 * <p>Two properties the caller can rely on:
 *
 * <p><strong>Deterministic.</strong> The same interview id, template and
 * catalogue always produce the same plan. Randomness is seeded from the
 * interview id, so plans vary across candidates but are perfectly reproducible
 * for any one of them — which is what makes a planning bug debuggable rather
 * than a ghost.
 *
 * <p><strong>No repeats.</strong> A question family appears at most once in a
 * plan, and a family the candidate has already met is avoided <em>where the
 * pool allows</em>. Exposure avoidance is a preference, not a constraint: with
 * a small bank, refusing to repeat would mean refusing to start an interview,
 * and a repeated question is much less bad than no interview at all.
 */
@Component
public class QuestionSelector {

    private final QuestionCatalog questionCatalog;

    public QuestionSelector(QuestionCatalog questionCatalog) {
        this.questionCatalog = questionCatalog;
    }

    /**
     * Resolves one slot.
     *
     * @param alreadyChosen question families already in this plan
     * @param previouslySeen families the candidate met in earlier attempts
     * @param seed          the interview id, making the choice reproducible
     * @return empty when nothing eligible remains, which the planner treats as
     *         a planning failure rather than quietly serving a short interview
     */
    public Optional<QuestionVersionView> selectFor(QuestionSlotView slot,
                                                   Set<UUID> alreadyChosen,
                                                   Set<UUID> previouslySeen,
                                                   UUID seed) {
        if (slot.isPinned()) {
            return selectPinned(slot, alreadyChosen);
        }
        return selectFromPool(slot, alreadyChosen, previouslySeen, seed);
    }

    /**
     * A pinned slot names its question, so there is nothing to choose — only to
     * check that it is still publishable and not already used.
     */
    private Optional<QuestionVersionView> selectPinned(QuestionSlotView slot,
                                                       Set<UUID> alreadyChosen) {
        if (alreadyChosen.contains(slot.questionId())) {
            return Optional.empty();
        }
        return questionCatalog.findPublishedVersionOfQuestion(slot.questionId());
    }

    private Optional<QuestionVersionView> selectFromPool(QuestionSlotView slot,
                                                         Set<UUID> alreadyChosen,
                                                         Set<UUID> previouslySeen,
                                                         UUID seed) {
        List<QuestionVersionView> eligible = new ArrayList<>(
                questionCatalog.findPublishedVersionsForPool(
                        slot.skillId(), slot.difficulty(), slot.questionType()));

        eligible.removeIf(version -> alreadyChosen.contains(version.questionId()));
        if (eligible.isEmpty()) {
            // Widening the difficulty here would quietly change what the
            // template promised, so the planner is told instead.
            return Optional.empty();
        }

        // Unseen questions first; within each group, a stable shuffle driven by
        // the interview id. Sorting on a derived key rather than shuffling a
        // list keeps the result independent of the catalogue's return order.
        eligible.sort(Comparator
                .comparing((QuestionVersionView v) -> previouslySeen.contains(v.questionId()))
                .thenComparingLong(v -> mix(seed, v.id()))
                .thenComparing(v -> v.id().toString()));

        return Optional.of(eligible.get(0));
    }

    /**
     * A cheap, stable hash of (interview, question version).
     *
     * <p>Not cryptographic and does not need to be: it only has to spread
     * choices across candidates while being identical on every replay.
     */
    private static long mix(UUID seed, UUID candidate) {
        long h = seed.getMostSignificantBits() * 31 + seed.getLeastSignificantBits();
        h ^= candidate.getMostSignificantBits() * 1099511628211L;
        h ^= candidate.getLeastSignificantBits();
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }
}
