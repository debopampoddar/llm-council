package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mutable state container passed through all protocol stages.
 *
 * <p>One {@code CouncilContext} is created per protocol run and carries:
 * <ul>
 *   <li>The originating {@link CouncilSession} and {@link CouncilProfile}.</li>
 *   <li>Drafts accumulated by GENERATE / AGGREGATE.</li>
 *   <li>Reviews and scores from REVIEW / SCORE.</li>
 *   <li>Debate rounds from DEBATE.</li>
 *   <li>The final synthesis result from SYNTHESIZE.</li>
 *   <li>Terminal status if a stage fails fatally.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> collection fields use {@link CopyOnWriteArrayList}
 * so that stage executors running model calls on virtual threads can safely
 * call {@link #excludeModel} and {@link #recordModelFailure} concurrently.
 * Scalar fields that indicate terminal state are {@code volatile}.
 */
public class CouncilContext {

    private final CouncilSession session;
    private final CouncilProfile profile;
    private final CouncilPolicy policy;
    private final ProtocolDefinition protocol;

    // CopyOnWriteArrayList: writes are infrequent (one per model call) but
    // concurrent from virtual threads in GENERATE, AGGREGATE, and DEBATE stages.
    private final List<Draft> drafts = new CopyOnWriteArrayList<>();
    private final List<ReviewArtifact> reviews = new CopyOnWriteArrayList<>();
    private final List<ScoreArtifact> scores = new CopyOnWriteArrayList<>();
    private final List<DebateRound> debateRounds = new CopyOnWriteArrayList<>();
    private final List<String> excludedModels = new CopyOnWriteArrayList<>();
    private final List<ModelFailure> modelFailures = new CopyOnWriteArrayList<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    // Sycophancy warnings flagged during debate rounds.
    private final List<String> sycophancyWarnings = new CopyOnWriteArrayList<>();

    private volatile String synthesisResult;
    private volatile ScoreSummary scoreSummary;
    private volatile ValidationArtifact validation;
    private volatile boolean terminal = false;
    private volatile StageType failedStage;
    private volatile Throwable failureCause;

    /**
     * @param session  The originating council session.
     * @param profile  The council member/chair configuration.
     * @param protocol The protocol currently executing.
     */
    public CouncilContext(CouncilSession session,
                          CouncilProfile profile,
                          CouncilPolicy policy,
                          ProtocolDefinition protocol) {
        this.session = session;
        this.profile = profile;
        this.policy = policy;
        this.protocol = protocol;
    }

    // ── Session / Profile / Protocol 

    /** @return The council session this context belongs to. */
    public CouncilSession session() { return session; }

    /** @return The council profile (members + chair). */
    public CouncilProfile profile() { return profile; }

    /** @return The resolved execution policy for this run. */
    public CouncilPolicy policy() { return policy; }

    /** @return The protocol being executed. */
    public ProtocolDefinition protocol() { return protocol; }

    // ── Drafts 

    /** Add a new draft to the context. */
    public void addDraft(Draft draft) { drafts.add(draft); }

    /** @return Snapshot of all current drafts. Thread-safe via CopyOnWriteArrayList. */
    public List<Draft> drafts() { return List.copyOf(drafts); }

    /**
     * Replace all current drafts with the refined (aggregated) versions.
     * Called by {@link StageType#AGGREGATE} executor after fan-out.
     */
    public void clearDrafts() {
        // CopyOnWriteArrayList.clear() is atomic; safe to call from main thread
        // between stage boundaries.
        drafts.clear();
    }

    // ── Reviews 

    /** Add a review artifact. */
    public void addReview(ReviewArtifact review) { reviews.add(review); }

    /** @return Snapshot of review artifacts. Thread-safe via CopyOnWriteArrayList. */
    public List<ReviewArtifact> reviews() { return List.copyOf(reviews); }

    // ── Scores 

    /** Add a score artifact. */
    public void addScore(ScoreArtifact score) { scores.add(score); }

    /** @return Snapshot of score artifacts. Thread-safe via CopyOnWriteArrayList. */
    public List<ScoreArtifact> scores() { return List.copyOf(scores); }

    /** Replace the latest score summary. */
    public void setScoreSummary(ScoreSummary scoreSummary) { this.scoreSummary = scoreSummary; }

    /** @return The latest score summary, if scoring ran successfully. */
    public Optional<ScoreSummary> scoreSummary() { return Optional.ofNullable(scoreSummary); }

    // ── Debate 

    /** Add a completed debate round. */
    public void addDebateRound(DebateRound round) { debateRounds.add(round); }

    /** @return Snapshot of debate rounds. Thread-safe via CopyOnWriteArrayList. */
    public List<DebateRound> debateRounds() { return List.copyOf(debateRounds); }

    // ── Synthesis 

    /** Set the chair's final synthesised answer. */
    public void setSynthesisResult(String text) { this.synthesisResult = text; }

    /** @return The synthesised answer, or empty if SYNTHESIZE has not run yet. */
    public Optional<String> synthesisResult() { return Optional.ofNullable(synthesisResult); }

    // ── Validation 

    /** Set the Fresh Eyes validation result. */
    public void setValidation(ValidationArtifact validation) { this.validation = validation; }

    /** @return Fresh Eyes validation result, if validation ran. */
    public Optional<ValidationArtifact> validation() { return Optional.ofNullable(validation); }

    // ── Model exclusions / warnings 

    /** Record a model that failed or was excluded by policy. */
    public void excludeModel(String modelId, String reason) {
        excludedModels.add(modelId + ": " + reason);
    }

    /** @return Models excluded from the final result with reasons. */
    public List<String> excludedModels() { return List.copyOf(excludedModels); }

    /** Record a structured model call failure for API consumers. */
    public void recordModelFailure(ModelProfile model, ModelCallException failure) {
        modelFailures.add(new ModelFailure(
                model.id(),
                failure.provider() != null ? failure.provider() : model.provider(),
                failure.providerModelId() != null ? failure.providerModelId() : model.providerModelId(),
                failure.category().name(),
                failure.getMessage()));
    }

    /** @return Structured model call failures accumulated during the run. */
    public List<ModelFailure> modelFailures() { return List.copyOf(modelFailures); }

    /** Add a non-terminal warning for API and event consumers. */
    public void addWarning(String warning) { warnings.add(warning); }

    /** @return Non-terminal warnings accumulated during the run. */
    public List<String> warnings() { return List.copyOf(warnings); }

    /** Record a sycophancy warning detected during debate. Gap 1.3. */
    public void addSycophancyWarning(String warning) { sycophancyWarnings.add(warning); }

    /** @return Sycophancy warnings detected during debate rounds. Gap 1.3. */
    public List<String> sycophancyWarnings() { return List.copyOf(sycophancyWarnings); }

    // ── Terminal state 

    /**
     * Mark this context as terminal due to a stage failure.
     * Subsequent stages will be skipped by the orchestrator.
     *
     * @param stage The stage that failed.
     * @param cause The exception that caused the failure.
     */
    public void markFailed(StageType stage, Throwable cause) {
        this.terminal = true;
        this.failedStage = stage;
        this.failureCause = cause;
    }

    /**
     * @return {@code true} if a previous stage called {@link #markFailed}.
     */
    public boolean isTerminal() { return terminal; }

    /** @return The stage that marked this context terminal, or {@code null}. */
    public StageType failedStage() { return failedStage; }

    /** @return The exception that caused the terminal state, or {@code null}. */
    public Throwable failureCause() { return failureCause; }

    /** @return User-facing failure text, if this context is terminal. */
    public Optional<String> failureMessage() {
        if (failureCause == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(failureCause.getMessage())
                       .or(() -> Optional.of(failureCause.getClass().getSimpleName()));
    }
}
