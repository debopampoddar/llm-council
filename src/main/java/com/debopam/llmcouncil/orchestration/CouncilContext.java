package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.model.CouncilProfile;

import java.util.ArrayList;
import java.util.List;

public class CouncilContext {
    private final CouncilSession session;
    private final CouncilProfile profile;
    private final List<Draft> drafts = new ArrayList<>();
    private final List<PeerReviewOutput> reviews = new ArrayList<>();
    private AnonymizedDraftSet anonymizedDraftSet;
    private ScoreSummary scoreSummary;
    private String finalAnswer;
    private ValidationOutput validationOutput;

    public CouncilContext(CouncilSession session, CouncilProfile profile) {
        this.session = session;
        this.profile = profile;
    }

    public CouncilSession session() {
        return session;
    }

    public CouncilProfile profile() {
        return profile;
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

    public void setScoreSummary(ScoreSummary scoreSummary) {
        this.scoreSummary = scoreSummary;
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
}
