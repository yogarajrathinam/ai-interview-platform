package com.aiinterview.interviewplatform.interview.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The candidate's work for one turn.
 *
 * <p>The primary key <em>is</em> the turn id. That single constraint is the
 * whole idempotency story for answer submission: two concurrent submits both
 * attempt the insert, exactly one wins, and the loser's unique violation is
 * translated into a {@code 200} carrying the stored answer. No client-supplied
 * submission id, no idempotency-key table.
 *
 * <p>Content is immutable ({@code trg_answers_20_immutable}) — after
 * submitting, a candidate cannot revise, which matches a real interview and is
 * safer than an overwrite path.
 *
 * <p>Skipped turns create <strong>no row here</strong>; the turn's status
 * carries that fact. {@code inputMode} is the seam for voice: the evaluation
 * engine only ever consumes normalised text, whatever produced it.
 */
@Entity
@Table(name = "answers", schema = "app")
public class AnswerEntity {

    public enum InputMode { TEXT, VOICE }

    /** Shared PK and FK: {@code interview_questions.id}. */
    @Id
    @Column(name = "interview_question_id", nullable = false, updatable = false)
    private UUID interviewQuestionId;

    @Column(name = "content_text", nullable = false, updatable = false)
    private String contentText;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_mode", nullable = false, updatable = false)
    private InputMode inputMode;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private OffsetDateTime submittedAt;

    protected AnswerEntity() {
        // for JPA
    }

    public UUID getInterviewQuestionId() { return interviewQuestionId; }
    public String getContentText() { return contentText; }
    public InputMode getInputMode() { return inputMode; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
}
