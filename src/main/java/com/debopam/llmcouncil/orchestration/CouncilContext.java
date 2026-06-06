package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.export.ExportManifest;
import com.debopam.llmcouncil.model.CouncilProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable working context for a single council run.
 *
 * <p>Holds the immutable session/profile/protocol trio plus all
 * intermediate artifacts (drafts, reviews, scores, debate summary,
 * validation output, export manifest).</p>
 *
 * <p>It also tracks terminal state so that the {@link ProtocolOrchestrator}
 * can short‑circuit remaining stages after a fatal failure.</p>
 */
public class CouncilContext {

    private final CouncilSession session;
    private final CouncilProfile profile;
    private final ProtocolDefinition protocol;

    private final List<Draft> drafts = new ArrayList<>();
    private final List<PeerReviewOutput> reviews = new ArrayList<>();
    private final List<ScoreSnapshot> scoreSnapshots = new ArrayList<>();

    private AnonymizedDraftSet anonymizedDraftSet;
    private ScoreSummary scoreSummary;
    private DebateSummary debateSummary;
    private String finalAnswer;
    private ValidationOutput validationOutput;
    private ExportManifest exportManifest;

    // Terminal / failure tracking for robust orchestration
    private boolean terminal;
    private StageType failureStage;
    private String failureReason;

    public CouncilContext(CouncilSession session,
                          CouncilProfile profile,
                          ProtocolDefinition protocol) {
        this.session = session;
        this.profile = profile;
        this.protocol = protocol;
    }

    // --- Core identity ---

    public CouncilSession session() {
        return session;
    }

    public CouncilProfile profile() {
        return profile;
    }

    public ProtocolDefinition protocol() {
        return protocol;
    }

    // --- Drafts ---

    public List<Draft> drafts() {
        return List.copyOf(drafts);
    }

    public void addDraft(Draft draft) {
        drafts.add(draft);
    }

    // --- Anonymized drafts ---

    public AnonymizedDraftSet anonymizedDraftSet() {
        return anonymizedDraftSet;
    }

    public void setAnonymizedDraftSet(AnonymizedDraftSet anonymizedDraftSet) {
        this.anonymizedDraftSet = anonymizedDraftSet;
    }

    // --- Reviews ---

    public List<PeerReviewOutput> reviews() {
        return List.copyOf(reviews);
    }

    public void addReview(PeerReviewOutput review) {
        reviews.add(review);
    }

    // --- Scores ---

    public ScoreSummary scoreSummary() {
        return scoreSummary;
    }

    /**
     * Set the current score summary and capture a labeled snapshot for later
     * inspection (e.g. initial vs post‑debate scores).
     */
    public void setScoreSummary(String label, ScoreSummary scoreSummary) {
        this.scoreSummary = scoreSummary;
        this.scoreSnapshots.add(new ScoreSnapshot(label, scoreSummary));
    }

    public List<ScoreSnapshot> scoreSnapshots() {
        return List.copyOf(scoreSnapshots);
    }

    // --- Debate ---

    public DebateSummary debateSummary() {
        return debateSummary;
    }

    public void setDebateSummary(DebateSummary debateSummary) {
        this.debateSummary = debateSummary;
    }

    // --- Final answer ---

    public String finalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    // --- Validation ---

    public ValidationOutput validationOutput() {
        return validationOutput;
    }

    public void setValidationOutput(ValidationOutput validationOutput) {
        this.validationOutput = validationOutput;
    }

    // --- Export manifest ---

    public ExportManifest exportManifest() {
        return exportManifest;
    }

    public void setExportManifest(ExportManifest exportManifest) {
        this.exportManifest = exportManifest;
    }

    // --- Terminal / failure state for ProtocolOrchestrator ---

    /**
     * Whether the context has been marked terminal. When true, the
     * {@link ProtocolOrchestrator} should skip all remaining stages.
     */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Mark the context as failed at a given stage. This does not throw;
     * the orchestrator is responsible for surfacing the error via events
     * and persisting failure state on {@link CouncilSession}.
     */
    public void markFailed(StageType stage, Throwable ex) {
        this.terminal = true;
        this.failureStage = stage;
        this.failureReason = ex != null ? ex.getMessage() : null;
    }

    /**
     * Stage at which a fatal failure occurred, if any.
     */
    public StageType failureStage() {
        return failureStage;
    }

    /**
     * Human‑readable failure reason, if any. This is primarily for diagnostics;
     * durable failure information should be copied onto {@link CouncilSession}.
     */
    public String failureReason() {
        return failureReason;
    }
}
