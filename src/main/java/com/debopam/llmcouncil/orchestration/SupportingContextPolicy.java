package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.domain.ContextPurpose;

/**
 * Applies the declared purpose of supporting context before orchestration.
 *
 * <p>The original session remains available to the application for audit and
 * persistence. The execution copy returned here is the only copy passed to
 * model-facing stages.
 */
public final class SupportingContextPolicy {

    private SupportingContextPolicy() {
    }

    /**
     * Prepare context for model consumption.
     *
     * @param context raw supporting context
     * @param purpose declared use; null safely means {@link ContextPurpose#EVIDENCE}
     * @return unchanged analysis material or deterministically sanitised evidence
     */
    public static String prepare(String context, ContextPurpose purpose) {
        return purpose == ContextPurpose.ANALYSIS_SUBJECT
                ? context
                : TrustBoundaryGuard.sanitize(context);
    }
}
