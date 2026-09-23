package com.aiinterview.interviewplatform.evaluation.application;

import com.aiinterview.interviewplatform.evaluation.api.ScoreAggregator;
import com.aiinterview.interviewplatform.evaluation.domain.InterviewScorer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Adapts the published aggregation port onto the domain scorer.
 *
 * <p>Translation and nothing else — every rule about what counts, what is
 * excluded and how the renormalisation works stays in {@link InterviewScorer}.
 * If a single arithmetic decision ever appears in this class, the formula has
 * begun to exist in two places, which is the failure this seam prevents.
 */
@Service
public class DefaultScoreAggregator implements ScoreAggregator {

    @Override
    public AggregateScore aggregate(List<ScoredItem> items, Map<UUID, Integer> skillWeights) {
        List<InterviewScorer.ScoredQuestion> questions =
                (items == null ? List.<ScoredItem>of() : items).stream()
                        .map(item -> new InterviewScorer.ScoredQuestion(
                                item.itemId(), item.skillId(), item.weightBp(), item.score()))
                        .toList();

        InterviewScorer.InterviewScore scored = InterviewScorer.score(
                questions, skillWeights == null ? Map.of() : skillWeights);

        return new AggregateScore(
                scored.overallScore(),
                scored.skillScores().stream()
                        .map(skill -> new SkillAggregate(
                                skill.skillId(), skill.score(), skill.skillWeightBp(),
                                skill.coverageBp(), skill.questionCount()))
                        .toList(),
                scored.coverageBp());
    }
}
