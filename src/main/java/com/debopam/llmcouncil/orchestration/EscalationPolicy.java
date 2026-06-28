package com.debopam.llmcouncil.orchestration;

/**
 * Defines how the council should handle persistent disagreement between
 * reviewers/debaters after scoring and/or debate stages.
 *
 * <p><b>Gap 2.3 (Disagreement Escalation):</b> when score variance remains
 * high after debate, the council should not silently proceed to synthesis.
 * Instead, the escalation policy determines the appropriate response.
 *
 * <p>Configured per protocol via the {@code escalation-policy} stage option
 * on the SCORE stage.
 */
public enum EscalationPolicy {

    /**
     * Default behaviour: proceed to synthesis even if disagreement persists.
     * The synthesis prompt should note the dissent for the chair model.
     */
    SYNTHESIZE_WITH_DISSENT,

    /**
     * Flag the session for human review. The council still produces a
     * synthesis, but the session metadata and validation artifact will
     * indicate that human verification is recommended.
     */
    FLAG_FOR_HUMAN_REVIEW,

    /**
     * Halt the council run and mark the session as requiring escalation.
     * No synthesis is attempted. Suitable for high-stakes use cases
     * (medical, legal, financial advice) where unresolved disagreement
     * is a blocker.
     */
    HALT_AND_ESCALATE
}
