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
            You are a Fresh Eyes validator. You did not participate in generation, review, debate, or synthesis.
            Evaluate only the final answer against the original question.
            Use a rubric and return only valid JSON:
            approved, confidence, issues, recommendedFixes, criteria, requiresHumanReview.
            Criteria must include: correctness, completeness, uncertainty, safety, actionability.
            """),
                ChatMessage.user("Question:\n" + question + "\n\nFinal answer:\n" + finalAnswer)
        );
    }

    public List<ChatMessage> debateMessages(
            String modelId,
            String question,
            AnonymizedDraftSet draftSet,
            ScoreSummary scoreSummary,
            DebateSummary previousDebate,
            int roundNumber
    ) {
        return List.of(
                ChatMessage.system("""
            You are a bounded debate participant in an LLM council.
            Your job is to stress-test the current draft rankings, not to win an argument.
            Use evidence from the question, drafts, reviews, and score summary.
            Do not invent external facts.
            Change position only when evidence justifies it.
            Return only valid JSON with fields:
            position, supportedDraftIds, challengedDraftIds, newEvidence, unresolvedRisks,
            changedPosition, changeReason, confidence.
            confidence must be a number from 0.0 to 1.0.
            """),
                ChatMessage.user("""
            Participant model: %s
            Debate round: %d

            Original question:
            %s

            Anonymized drafts:
            %s

            Current score summary:
            %s

            Previous debate summary:
            %s
            """.formatted(modelId, roundNumber, question, draftSet.drafts(), scoreSummary, previousDebate))
        );
    }

    public List<ChatMessage> synthesisMessages(
            String question,
            List<Draft> drafts,
            ScoreSummary scores,
            List<PeerReviewOutput> reviews,
            DebateSummary debateSummary
    ) {
        return List.of(
                ChatMessage.system("""
            You are the council chair.
            Synthesize the best final answer from drafts, reviews, scores, and debate.
            Preserve material dissent when the evidence is unresolved.
            Do not hide uncertainty.
            Separate:
            1. final recommendation or answer
            2. reasoning summary
            3. important dissent
            4. risks and assumptions
            5. confidence
            """),
                ChatMessage.user("""
            Question:
            %s

            Drafts:
            %s

            Scores:
            %s

            Reviews:
            %s

            Debate summary:
            %s
            """.formatted(question, drafts, scores, reviews, debateSummary))
        );
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
