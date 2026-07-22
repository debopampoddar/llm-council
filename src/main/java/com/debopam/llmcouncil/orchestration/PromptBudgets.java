package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;

import java.util.Map;

/**
 * Surfaces {@link PromptBudget} truncation on the council run.
 *
 * <p>Truncation is a quality event, not a logging detail. If a chair synthesised
 * an answer from a fraction of the council's evidence, that fact belongs in the
 * run result where a reader will see it — otherwise the run is indistinguishable
 * from one that considered everything.
 */
final class PromptBudgets {

    private PromptBudgets() {
    }

    /**
     * Record any truncation performed while building a prompt.
     *
     * <p>No-op when nothing was truncated, which is the common case.
     *
     * @param ctx     the run to record warnings on
     * @param events  publisher for the {@code CONTEXT_BUDGET_EXCEEDED} event
     * @param stage   the stage whose prompt was budgeted
     * @param modelId the model the prompt was built for
     * @param budget  the budget used to build the prompt
     */
    static void record(CouncilContext ctx, EventPublisher events, StageType stage,
                       String modelId, PromptBudget budget) {
        if (!budget.truncated()) {
            return;
        }
        for (String notice : budget.notices()) {
            ctx.addWarning(notice);
            events.publish(ctx.session().id(), stage.name(), "CONTEXT_BUDGET_EXCEEDED", modelId,
                           Map.of("detail", notice, "budgetChars", budget.totalChars()));
        }
    }
}
