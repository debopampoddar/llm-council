// ── DebateStageExecutor.java ──────────────────────────────────────────────
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DEBATE stage: runs multi-round argument exchange with adaptive stopping.
 * Uses the KS convergence test (Hu et al., NeurIPS 2025) to stop early
 * when agent confidence distributions stabilise.
 */
@Component
public class DebateStageExecutor implements StageExecutor {
    private static final Logger log = LoggerFactory.getLogger(DebateStageExecutor.class);
    private static final Pattern CONFIDENCE_PATTERN = Pattern.compile("Confidence:\\s*(\\d+)");

    private final ModelRegistry registry;
    private final PromptBuilder promptBuilder;
    private final EventPublisher events;

    public DebateStageExecutor(ModelRegistry registry, PromptBuilder promptBuilder, EventPublisher events) {
        this.registry = registry; this.promptBuilder = promptBuilder; this.events = events;
    }

    @Override public StageType stage() { return StageType.DEBATE; }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) throws Exception {
        int maxRounds = opts.getInt("max-rounds", 3);
        double ksThreshold = opts.getDouble("ks-convergence-threshold", 0.10);
        boolean forceRun = opts.getBoolean("force-run", false);

        double varianceTrigger = opts.getDouble("debate-trigger-score-variance", 120.0);

        if (!forceRun && ctx.scoreSummary().map(ScoreSummary::variance).orElse(0.0) < varianceTrigger) {
            events.publish(ctx.session().id(), stage().name(), "DEBATE_SKIPPED", null,
                           Map.of("reason", "score variance below threshold",
                                  "variance", ctx.scoreSummary().map(ScoreSummary::variance).orElse(0.0),
                                  "threshold", varianceTrigger));
            return ctx;
        }

        DebateConvergenceDetector convergence = new DebateConvergenceDetector(ksThreshold);
        List<Double> prevScores = null;

        for (int round = 0; round < maxRounds; round++) {
            events.publish(ctx.session().id(), stage().name(), "DEBATE_ROUND_STARTED", null,
                           Map.of("round", round));
            DebateRound debateRound = runRound(ctx, round);
            ctx.addDebateRound(debateRound);

            List<Double> currScores = debateRound.confidenceScores();
            if (convergence.hasConverged(prevScores, currScores)) {
                events.publish(ctx.session().id(), stage().name(), "DEBATE_CONVERGED", null,
                               Map.of("round", round, "ksThreshold", ksThreshold));
                break;
            }
            prevScores = currScores;
        }
        return ctx;
    }

    private DebateRound runRound(CouncilContext ctx, int round) {
        List<DebateContribution> contributions = Collections.synchronizedList(new ArrayList<>());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = ctx.policy().memberModelIds().stream()
                             .map(modelId -> executor.submit(() -> contribute(ctx, modelId, round)))
                             .toList();
            for (var f : futures) {
                try { DebateContribution c = f.get(); if (c != null) contributions.add(c); }
                catch (Exception ex) { log.warn("Debate contribution failed", ex); }
            }
        }
        return new DebateRound(round, List.copyOf(contributions));
    }

    private DebateContribution contribute(CouncilContext ctx, String modelId, int round) {
        ModelProfile model = registry.model(modelId);
        try {
            ModelCallResult result = registry.clientForModel(modelId).call(
                    new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                                         model.providerModelId(),
                                         promptBuilder.debateMessages(ctx.session().question(),
                                                                      ctx.session().context(), ctx.drafts(), ctx.debateRounds(), round),
                                         model.defaultOutputTokens(), model.temperature(), false, model.defaultTimeout()));
            int confidence = parseConfidence(result.text());
            return new DebateContribution(modelId, result.text(), confidence);
        } catch (ModelCallException ex) {
            events.publish(ctx.session().id(), stage().name(), "DEBATE_CONTRIBUTION_FAILED",
                           modelId, Map.of("error", ex.getMessage()));
            ctx.excludeModel(modelId, "debate failed: " + ex.getMessage());
            return null;
        }
    }

    private int parseConfidence(String text) {
        Matcher m = CONFIDENCE_PATTERN.matcher(text);
        return m.find() ? Math.min(100, Math.max(0, Integer.parseInt(m.group(1)))) : 70;
    }
}
