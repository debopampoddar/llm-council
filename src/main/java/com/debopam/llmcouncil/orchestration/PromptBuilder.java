package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.model.ChatMessage;

import java.util.List;

public class PromptBuilder {
    public List<ChatMessage> generationMessages(String question, String context) {
        return List.of(
                ChatMessage.system("You are an independent council member. Answer directly, state assumptions, and avoid referencing other models."),
                ChatMessage.user("Question:\n" + question + "\n\nContext:\n" + nullToEmpty(context))
        );
    }

    public List<ChatMessage> reviewMessages(String question, List<AnonymizedDraft> drafts) {
        return List.of(
                ChatMessage.system("""
                You are a strict reviewer. Score each anonymized draft from 1 to 100 for accuracy and completeness.
                Return only valid JSON with fields: evaluations, reviewerConfidence, globalConcerns.
                Each evaluation must include draftId, scores, strengths, weaknesses, evidenceRequired, wouldChangePosition, whatWouldChangeMyMind.
                """),
                ChatMessage.user("Question:\n" + question + "\n\nDrafts:\n" + drafts)
        );
    }

    public List<ChatMessage> synthesisMessages(String question, List<Draft> drafts, ScoreSummary scores, List<PeerReviewOutput> reviews) {
        return List.of(
                ChatMessage.system("You are the council chair. Synthesize the best answer. Preserve important dissent and unresolved risks."),
                ChatMessage.user("Question:\n" + question + "\n\nDrafts:\n" + drafts + "\n\nScores:\n" + scores + "\n\nReviews:\n" + reviews)
        );
    }

    public List<ChatMessage> validationMessages(String question, String finalAnswer) {
        return List.of(
                ChatMessage.system("""
                You are a Fresh Eyes validator. You did not participate in the council.
                Return only JSON: approved, confidence, issues, recommendedFixes.
                """),
                ChatMessage.user("Question:\n" + question + "\n\nFinal answer:\n" + finalAnswer)
        );
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
