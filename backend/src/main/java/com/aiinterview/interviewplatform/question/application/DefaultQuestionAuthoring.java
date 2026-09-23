package com.aiinterview.interviewplatform.question.application;

import com.aiinterview.interviewplatform.question.api.QuestionAuthoring;
import com.aiinterview.interviewplatform.question.api.QuestionCatalog;
import com.aiinterview.interviewplatform.question.domain.QuestionEntity;
import com.aiinterview.interviewplatform.question.domain.QuestionVersionEntity;
import com.aiinterview.interviewplatform.question.domain.RubricCriterionEntity;
import com.aiinterview.interviewplatform.question.infrastructure.QuestionRepository;
import com.aiinterview.interviewplatform.question.infrastructure.QuestionVersionRepository;
import com.aiinterview.interviewplatform.question.infrastructure.RubricCriterionRepository;
import com.aiinterview.interviewplatform.shared.error.ApplicationException;
import com.aiinterview.interviewplatform.shared.error.ErrorCode;
import com.aiinterview.interviewplatform.shared.id.IdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoring for the question bank.
 *
 * <p>Validation happens here <em>and</em> in the database. The triggers are the
 * authority and cannot be bypassed; this layer exists so an author is told
 * "the rubric weights come to 9000, not 10000" instead of receiving a
 * constraint violation. Both matter: one is correctness, the other is usability.
 */
@Service
public class DefaultQuestionAuthoring implements QuestionAuthoring {

    private static final Logger log = LoggerFactory.getLogger(DefaultQuestionAuthoring.class);

    /** Mirrors {@code trg_qv_10_validate}; kept aligned deliberately. */
    private static final int MIN_CRITERIA = 3;
    private static final int MAX_CRITERIA = 10;
    private static final int FULL_WEIGHT_BP = 10_000;

    private final QuestionRepository questions;
    private final QuestionVersionRepository versions;
    private final RubricCriterionRepository criteria;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public DefaultQuestionAuthoring(QuestionRepository questions,
                                    QuestionVersionRepository versions,
                                    RubricCriterionRepository criteria,
                                    IdGenerator idGenerator, Clock clock) {
        this.questions = questions;
        this.versions = versions;
        this.criteria = criteria;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DraftVersion createQuestion(CreateQuestionCommand command) {
        if (questions.existsByQuestionKey(command.questionKey())) {
            throw ApplicationException.conflict(ErrorCode.CONFLICT,
                    "A question with key '%s' already exists.".formatted(command.questionKey()));
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        QuestionEntity question = questions.save(QuestionEntity.create(
                idGenerator.newId(), command.questionKey(), command.authorId(), now));

        QuestionVersionEntity version = versions.save(QuestionVersionEntity.draft(
                idGenerator.newId(), question.getId(), 1, command.skillId(),
                parseType(command.questionType()), parseDifficulty(command.difficulty()),
                command.promptText(), command.contextText(), command.referenceAnswer(),
                command.expectedDurationSec() == null ? 120 : command.expectedDurationSec(),
                command.authorId(), now));

        log.info("Question draft created: key={} questionId={} versionId={} author={}",
                command.questionKey(), question.getId(), version.getId(), command.authorId());
        return toDraft(question, version);
    }

    @Override
    @Transactional
    public DraftVersion updateDraft(UpdateQuestionDraftCommand command) {
        QuestionVersionEntity version = requireVersion(command.questionVersionId());
        requireDraft(version);

        version.updateDraft(command.skillId(), parseType(command.questionType()),
                parseDifficulty(command.difficulty()), command.promptText(),
                command.contextText(), command.referenceAnswer(),
                command.expectedDurationSec(), OffsetDateTime.now(clock));
        versions.save(version);

        return toDraft(requireQuestion(version.getQuestionId()), version);
    }

    @Override
    @Transactional
    public DraftVersion replaceRubric(ReplaceRubricCommand command) {
        QuestionVersionEntity version = requireVersion(command.questionVersionId());
        requireDraft(version);

        List<String> problems = validateRubricShape(command.criteria());
        if (!problems.isEmpty()) {
            throw ApplicationException.validation(String.join(" ", problems));
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        criteria.deleteAll(
                criteria.findByQuestionVersionIdOrderBySortOrderAscIdAsc(version.getId()));
        // Flushed before the replacements are inserted: Hibernate orders inserts
        // ahead of deletes within a flush, so re-using a criterion code would
        // collide with uq_rc_code against a row that is about to disappear.
        criteria.flush();

        short order = 0;
        List<RubricCriterionEntity> replacements = new ArrayList<>();
        for (CriterionDraft draft : command.criteria()) {
            replacements.add(RubricCriterionEntity.create(
                    idGenerator.newId(), version.getId(), draft.code(), draft.label(),
                    draft.expectation(), draft.weightBp(), parseTier(draft.tier()),
                    draft.followUpPrompt(), order++, now));
        }
        criteria.saveAll(replacements);

        return toDraft(requireQuestion(version.getQuestionId()), version);
    }

    @Override
    @Transactional
    public PublishedVersion publish(PublishQuestionCommand command) {
        QuestionVersionEntity version = requireVersion(command.questionVersionId());
        requireDraft(version);

        List<String> problems = validateForPublication(version);
        if (!problems.isEmpty()) {
            // Every problem at once: an author fixing one only to be told about
            // the next wastes a round trip each time.
            log.info("Question publish rejected: versionId={} problems={}",
                    version.getId(), problems.size());
            throw ApplicationException.validation(String.join(" ", problems));
        }

        version.publish(command.authorId(), OffsetDateTime.now(clock));
        versions.save(version);

        log.info("Question published: questionId={} versionId={} version={} author={}",
                version.getQuestionId(), version.getId(), version.getVersion(),
                command.authorId());
        QuestionEntity question = requireQuestion(version.getQuestionId());
        return new PublishedVersion(question.getId(), version.getId(),
                question.getQuestionKey(), version.getVersion());
    }

    @Override
    @Transactional
    public DraftVersion createNextVersion(UUID questionId, UUID authorId) {
        QuestionEntity question = requireQuestion(questionId);
        QuestionVersionEntity published = versions
                .findByQuestionIdAndStatus(questionId, QuestionVersionEntity.Status.PUBLISHED)
                .orElseThrow(() -> ApplicationException.conflict(ErrorCode.CONFLICT,
                        "Question %s has no published version to base a new one on."
                                .formatted(question.getQuestionKey())));

        OffsetDateTime now = OffsetDateTime.now(clock);

        // Copied, never moved: the published row stays exactly as it is, so
        // every interview that pinned it grades against the same words forever.
        QuestionVersionEntity next = versions.save(QuestionVersionEntity.draft(
                idGenerator.newId(), questionId, published.getVersion() + 1,
                published.getSkillId(), published.getQuestionType(), published.getDifficulty(),
                published.getPromptText(), published.getContextText(),
                published.getReferenceAnswer(), published.getExpectedDurationSec(),
                authorId, now));

        short order = 0;
        List<RubricCriterionEntity> copies = new ArrayList<>();
        for (RubricCriterionEntity source
                : criteria.findByQuestionVersionIdOrderBySortOrderAscIdAsc(published.getId())) {
            copies.add(RubricCriterionEntity.create(idGenerator.newId(), next.getId(),
                    source.getCode(), source.getLabel(), source.getExpectation(),
                    source.getWeightBp(), source.getTier(), source.getFollowUpPrompt(),
                    order++, now));
        }
        criteria.saveAll(copies);

        log.info("Question next version drafted: questionId={} fromVersion={} newVersionId={}",
                questionId, published.getVersion(), next.getId());
        return toDraft(question, next);
    }

    // ---------------------------------------------------------- validation

    private List<String> validateRubricShape(List<CriterionDraft> drafts) {
        List<String> problems = new ArrayList<>();
        if (drafts.size() < MIN_CRITERIA || drafts.size() > MAX_CRITERIA) {
            problems.add("A rubric needs %d-%d criteria (got %d)."
                    .formatted(MIN_CRITERIA, MAX_CRITERIA, drafts.size()));
        }

        int total = 0;
        Set<String> codes = new HashSet<>();
        for (CriterionDraft draft : drafts) {
            if (draft.code() == null || draft.code().isBlank()) {
                problems.add("Every criterion needs a code.");
            } else if (!codes.add(draft.code())) {
                problems.add("Duplicate criterion code '%s'.".formatted(draft.code()));
            }
            if (draft.expectation() == null || draft.expectation().isBlank()) {
                problems.add("Criterion '%s' needs an expectation stating the observable claim."
                        .formatted(draft.code()));
            }
            if (draft.weightBp() <= 0) {
                problems.add("Criterion '%s' needs a positive weight.".formatted(draft.code()));
            }
            total += Math.max(0, draft.weightBp());
        }
        if (!drafts.isEmpty() && total != FULL_WEIGHT_BP) {
            problems.add("Criterion weights must total %d basis points (got %d)."
                    .formatted(FULL_WEIGHT_BP, total));
        }
        return problems;
    }

    private List<String> validateForPublication(QuestionVersionEntity version) {
        List<String> problems = new ArrayList<>();
        if (version.getPromptText() == null || version.getPromptText().isBlank()) {
            problems.add("The question needs prompt text.");
        }
        if (version.getReferenceAnswer() == null || version.getReferenceAnswer().isBlank()) {
            // Grounding for the grader; markedly improves the partial/missing
            // distinction, and the database requires it too.
            problems.add("A published question needs a reference answer.");
        }
        if (version.getSkillId() == null) {
            problems.add("The question needs a skill.");
        }

        List<RubricCriterionEntity> rubric =
                criteria.findByQuestionVersionIdOrderBySortOrderAscIdAsc(version.getId());
        if (rubric.size() < MIN_CRITERIA || rubric.size() > MAX_CRITERIA) {
            problems.add("A published question needs %d-%d rubric criteria (has %d)."
                    .formatted(MIN_CRITERIA, MAX_CRITERIA, rubric.size()));
        }
        int total = rubric.stream().mapToInt(RubricCriterionEntity::getWeightBp).sum();
        if (!rubric.isEmpty() && total != FULL_WEIGHT_BP) {
            problems.add("Rubric weights must total %d basis points (got %d)."
                    .formatted(FULL_WEIGHT_BP, total));
        }
        return problems;
    }

    // ------------------------------------------------------------- helpers

    private void requireDraft(QuestionVersionEntity version) {
        if (!version.isDraft()) {
            throw ApplicationException.conflict(ErrorCode.VERSION_IMMUTABLE,
                    ("Question version %d is %s and cannot be edited. "
                            + "Create version %d instead.")
                            .formatted(version.getVersion(), version.getStatus(),
                                    version.getVersion() + 1));
        }
    }

    private QuestionVersionEntity requireVersion(UUID id) {
        return versions.findById(id).orElseThrow(() -> ApplicationException.notFound(
                "Question version %s does not exist.".formatted(id)));
    }

    private QuestionEntity requireQuestion(UUID id) {
        return questions.findById(id).orElseThrow(() -> ApplicationException.notFound(
                "Question %s does not exist.".formatted(id)));
    }

    private DraftVersion toDraft(QuestionEntity question, QuestionVersionEntity version) {
        List<QuestionCatalog.RubricCriterionView> rubric =
                criteria.findByQuestionVersionIdOrderBySortOrderAscIdAsc(version.getId()).stream()
                        .map(c -> new QuestionCatalog.RubricCriterionView(c.getId(), c.getCode(),
                                c.getLabel(), c.getExpectation(), c.getWeightBp(),
                                c.getTier() == null ? null : c.getTier().name(),
                                c.getFollowUpPrompt(),
                                c.getSortOrder() == null ? 0 : c.getSortOrder()))
                        .toList();

        return new DraftVersion(question.getId(), version.getId(), question.getQuestionKey(),
                version.getVersion(), version.getStatus().name(), rubric);
    }

    private static QuestionVersionEntity.QuestionType parseType(String value) {
        return value == null ? QuestionVersionEntity.QuestionType.CONCEPTUAL
                : QuestionVersionEntity.QuestionType.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static QuestionVersionEntity.Difficulty parseDifficulty(String value) {
        return value == null ? QuestionVersionEntity.Difficulty.MEDIUM
                : QuestionVersionEntity.Difficulty.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static RubricCriterionEntity.Tier parseTier(String value) {
        return value == null ? RubricCriterionEntity.Tier.CORE
                : RubricCriterionEntity.Tier.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
