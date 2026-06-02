package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.export.ExportManifest;
import com.debopam.llmcouncil.model.CouncilProfile;


import java.util.ArrayList;
import java.util.List;

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

    public CouncilContext(CouncilSession session,
                          CouncilProfile profile,
                          ProtocolDefinition protocol) {
        this.session = session;
        this.profile = profile;
        this.protocol = protocol;
    }

    public CouncilSession session() {
        return session;
    }

    public CouncilProfile profile() {
        return profile;
    }

    public ProtocolDefinition protocol() {
        return protocol;
    }

    public List<Draft> drafts() {
        return List.copyOf(drafts);
    }

    public void addDraft(Draft draft) {
        drafts.add(draft);
    }

    public AnonymizedDraftSet anonymizedDraftSet() {
        return anonymizedDraftSet;
    }

    public void setAnonymizedDraftSet(AnonymizedDraftSet anonymizedDraftSet) {
        this.anonymizedDraftSet = anonymizedDraftSet;
    }

    public List<PeerReviewOutput> reviews() {
        return List.copyOf(reviews);
    }

    public void addReview(PeerReviewOutput review) {
        reviews.add(review);
    }

    public ScoreSummary scoreSummary() {
        return scoreSummary;
    }

    public void setScoreSummary(String label, ScoreSummary scoreSummary) {
        this.scoreSummary = scoreSummary;
        this.scoreSnapshots.add(new ScoreSnapshot(label, scoreSummary));
    }

    public List<ScoreSnapshot> scoreSnapshots() {
        return List.copyOf(scoreSnapshots);
    }

    public DebateSummary debateSummary() {
        return debateSummary;
    }

    public void setDebateSummary(DebateSummary debateSummary) {
        this.debateSummary = debateSummary;
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public ValidationOutput validationOutput() {
        return validationOutput;
    }

    public void setValidationOutput(ValidationOutput validationOutput) {
        this.validationOutput = validationOutput;
    }

    public ExportManifest exportManifest() {
        return exportManifest;
    }

    public void setExportManifest(ExportManifest exportManifest) {
        this.exportManifest = exportManifest;
    }
}

