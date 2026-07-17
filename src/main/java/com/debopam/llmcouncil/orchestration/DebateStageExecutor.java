// ── DebateStageExecutor.java 
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
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DEBATE stage: runs multi-round argument exchange with adaptive stopping.
 * Uses the KS convergence test (Hu et al., NeurIPS 2025) to stop early
 * when agent confidence distributions stabilise.
 *
 * <p><b>Minimum Debate Rounds:</b> A configurable {@code min-rounds}
 * parameter prevents convergence detection from firing until enough rounds have
 * completed, mitigating premature convergence from sycophantic early agreement.
 *
 * <p><b>Robust Confidence Parsing:</b> Multiple regex patterns handle
 * the varied confidence formats that different LLMs produce (e.g., decimal
 * scales, percentages, prose phrasing). Unparseable values return
 * {@link OptionalInt#empty()} instead of a hard-coded default, so they do not
 * pollute convergence detection.
 */
@Component
public class DebateStageExecutor implements StageExecutor {
    private static final Logger log = LoggerFactory.getLogger(DebateStageExecutor.class);

    // Multiple patterns to handle varying LLM output formats 
    // Order matters: more specific patterns are tried first.
    private static final Pattern[] CONFIDENCE_PATTERNS = {
        // "Confidence: 85" or "confidence: 85%"
        Pattern.compile("(?i)confidence:?\\s*(\\d{1,3})\\s*%?"),
        // "Confidence: 0.85" or "confidence: .7" (decimal scale)
        Pattern.compile("(?i)confidence:?\\s*0?\\.(\\d{1,2})"),
        // "confidence score: 85" / "confidence level: 85" / "confidence is: 85"
        Pattern.compile("(?i)confidence\\s+(?:score|level|is):?\\s*(\\d{1,3})"),
        // "my confidence is 85" / "my confidence is: 90"
        Pattern.compile("(?i)my\\s+confidence\\s+is:?\\s*(\\d{1,3})"),
    };

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

        // min-rounds prevents the KS convergence check from firing
        // too early, which mitigates sycophantic "instant agreement" in round 1.
        int minRounds = opts.getInt("min-rounds", 2);

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

            // Detect sycophantic behavior from round 1 onward.
            // High text similarity + high confidence shift toward majority
            // signals opinion change without substantive argument change.
            if (round > 0) {
                double sycophancyThreshold = opts.getDouble("sycophancy-threshold", 0.70);
                SycophancyDetector sycophancyDetector = new SycophancyDetector(sycophancyThreshold);
                DebateRound prevRound = ctx.debateRounds().get(ctx.debateRounds().size() - 2);
                SycophancyDetector.SycophancyReport report = sycophancyDetector.analyze(prevRound, debateRound);
                if (report.sycophancyDetected()) {
                    for (var score : report.scores()) {
                        if (score.flagged()) {
                            String warning = "Sycophancy detected for model " + score.modelId()
                                    + ": index=" + String.format("%.3f", score.sycophancyIndex())
                                    + " (textSim=" + String.format("%.2f", score.textSimilarity())
                                    + ", confDelta=" + String.format("%.1f", score.confidenceDelta()) + ")";
                            ctx.addSycophancyWarning(warning);
                            events.publish(ctx.session().id(), stage().name(),
                                    "DEBATE_SYCOPHANCY_WARNING", score.modelId(),
                                    Map.of("sycophancyIndex", score.sycophancyIndex(),
                                           "textSimilarity", score.textSimilarity(),
                                           "confidenceDelta", score.confidenceDelta(),
                                           "threshold", sycophancyThreshold));
                        }
                    }
                }
            }

            List<Double> currScores = debateRound.confidenceScores();

            // Only check convergence after the minimum number of
            // rounds have completed. This ensures the debate has progressed
            // enough for positions to genuinely stabilise, rather than
            // converging on sycophantic first-round agreement.
            if (round < minRounds - 1) {
                events.publish(ctx.session().id(), stage().name(),
                               "DEBATE_CONVERGENCE_DEFERRED", null,
                               Map.of("round", round,
                                      "minRounds", minRounds,
                                      "reason", "minimum rounds not yet reached"));
            } else if (convergence.hasConverged(prevScores, currScores)) {
                events.publish(ctx.session().id(), stage().name(), "DEBATE_CONVERGED", null,
                               Map.of("round", round,
                                      "ksThreshold", ksThreshold,
                                      "minRounds", minRounds));
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
            // Use role-aware debate prompt so CRITIC models
            // challenge consensus and SYNTHESIZER models seek common ground.
            ModelCallResult result = registry.clientForModel(modelId).call(
                    new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                                         model.providerModelId(),
                                         promptBuilder.debateMessagesForRole(ctx.session().question(),
                                                                              ctx.session().context(), ctx.drafts(), ctx.debateRounds(), round,
                                                                              model.councilRole()),
                                         model.defaultOutputTokens(), model.temperature(), false, model.defaultTimeout()));

            // Attempt to parse confidence; mark as -1 if unparseable
            // so that DebateRound.confidenceScores() can exclude the value from
            // the KS convergence calculation rather than injecting a misleading
            // default.
            OptionalInt confidence = parseConfidence(result.text());
            if (confidence.isEmpty()) {
                log.warn("Could not parse confidence from debate contribution by {}, "
                         + "excluding from convergence calculation", modelId);
                events.publish(ctx.session().id(), stage().name(),
                               "DEBATE_CONFIDENCE_UNPARSEABLE", modelId, Map.of());
            }
            return new DebateContribution(modelId, result.text(), confidence.orElse(-1));
        } catch (ModelCallException ex) {
            events.publish(ctx.session().id(), stage().name(), "DEBATE_CONTRIBUTION_FAILED",
                           modelId, Map.of("error", ex.getMessage()));
            ctx.excludeModel(modelId, "debate failed: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Parse confidence from model debate output using multiple regex patterns.
     *
     * <p><b>Gap 4.4:</b> Returns {@link OptionalInt#empty()} when no pattern
     * matches, rather than injecting a hard-coded default (previously 70) that
     * would pollute convergence detection with synthetic agreement signals.
     *
     * @param text The full debate contribution text from a model.
     * @return The parsed confidence value (0–100), or empty if not found.
     */
    private OptionalInt parseConfidence(String text) {
        for (int i = 0; i < CONFIDENCE_PATTERNS.length; i++) {
            Pattern pattern = CONFIDENCE_PATTERNS[i];
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                String value = m.group(1);
                int raw = Integer.parseInt(value);

                // The second pattern (index 1) captures digits after the
                // decimal point, e.g. "0.85" captures "85", "0.7" captures "7".
                // A single-digit capture like "7" means 70%, not 7%.
                if (i == 1) {
                    raw = value.length() == 1 ? raw * 10 : raw;
                }

                return OptionalInt.of(Math.min(100, Math.max(0, raw)));
            }
        }
        return OptionalInt.empty();
    }
}
