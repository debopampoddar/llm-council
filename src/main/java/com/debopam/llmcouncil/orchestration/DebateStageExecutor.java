// ── DebateStageExecutor.java 
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ChatMessage;
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
import java.util.Optional;
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

    // One pattern captures the whole numeric token; scale is decided afterwards
    // by NORMALISATION, not by pattern ordering. The previous implementation
    // used an ordered array whose first entry, "confidence:?\s*(\d{1,3})",
    // matched the leading "0" of "Confidence: 0.85" and returned 0 — a
    // *successful* parse, so it bypassed the -1 sentinel and silently fed a
    // maximally-unconfident value into the majority median, the convergence
    // check, and the sycophancy index.
    private static final Pattern CONFIDENCE_PATTERN = Pattern.compile(
            "(?i)confidence\\s*(?:score|level)?\\s*(?:is\\s*)?:?\\s*([0-9]*\\.?[0-9]+)\\s*(%?)");

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

        // Debate is triggered by reviewers disagreeing about the same draft, not
        // by the drafts scoring far apart — the latter is high exactly when the
        // council agrees on a winner, which is the least reason to debate.
        //
        // When disagreement was never measurable (a two-member council reviews
        // each draft once, since self-review is excluded) the run must say so
        // rather than report the same "below threshold" as a council that looked.
        Optional<ScoreSummary> summary = ctx.scoreSummary();
        boolean measurable = summary.map(ScoreSummary::disagreementMeasurable).orElse(false);
        double disagreement = summary.map(ScoreSummary::reviewerDisagreement).orElse(0.0);

        if (!forceRun && !measurable) {
            String warning = "Debate was skipped without measuring disagreement: no draft had two "
                             + "reviewers, so reviewer disagreement does not exist for this council. "
                             + "Seat a third member to make it measurable, or set force-run.";
            ctx.addWarning(warning);
            events.publish(ctx.session().id(), stage().name(), "DEBATE_SKIPPED", null,
                           Map.of("reason", "reviewer disagreement not measurable",
                                  "measurable", false,
                                  "variance", summary.map(ScoreSummary::variance).orElse(0.0),
                                  "threshold", varianceTrigger));
            return ctx;
        }

        if (!forceRun && disagreement < varianceTrigger) {
            events.publish(ctx.session().id(), stage().name(), "DEBATE_SKIPPED", null,
                           Map.of("reason", "reviewer disagreement below threshold",
                                  "measurable", true,
                                  "reviewerDisagreement", disagreement,
                                  "variance", summary.map(ScoreSummary::variance).orElse(0.0),
                                  "threshold", varianceTrigger));
            return ctx;
        }

        double confidenceDelta = opts.getDouble("convergence-confidence-delta",
                                                DebateConvergenceDetector.DEFAULT_CONFIDENCE_DELTA);
        DebateConvergenceDetector convergence =
                new DebateConvergenceDetector(ksThreshold, confidenceDelta);
        DebateRound prevRound = null;

        for (int round = 0; round < maxRounds; round++) {
            events.publish(ctx.session().id(), stage().name(), "DEBATE_ROUND_STARTED", null,
                           Map.of("round", round));
            DebateRound debateRound = runRound(ctx, round);
            ctx.addDebateRound(debateRound);

            // A narrowed sample is not a clean one. Convergence and sycophancy
            // both read confidenceScores(), which drops unreadable entries, so a
            // round that lost most of its members would otherwise stabilise for
            // the wrong reason and report nothing about it.
            int unreadable = debateRound.unreadableConfidenceCount();
            if (unreadable > 0) {
                ctx.addWarning("Confidence was unreadable for " + unreadable + " of "
                               + debateRound.contributions().size()
                               + " members in debate round " + round
                               + "; they are excluded from convergence and sycophancy analysis.");
            }

            // Detect capitulation from round 1 onward: confidence moved toward
            // the majority while the reasoning behind it stood still, either
            // because the member's own argument barely changed or because its
            // new argument is the others' prior language.
            if (round > 0) {
                double similarityThreshold = opts.getDouble("sycophancy-threshold", 0.70);
                double sycophancyDelta = opts.getDouble("sycophancy-confidence-delta",
                                                        SycophancyDetector.DEFAULT_CONFIDENCE_DELTA);
                SycophancyDetector sycophancyDetector =
                        new SycophancyDetector(similarityThreshold, sycophancyDelta);
                DebateRound priorRound = ctx.debateRounds().get(ctx.debateRounds().size() - 2);
                SycophancyDetector.SycophancyReport report =
                        sycophancyDetector.analyze(priorRound, debateRound);
                for (var score : report.scores()) {
                    if (!score.flagged()) {
                        continue;
                    }
                    String warning = "Sycophancy detected for model " + score.modelId()
                            + ": confidence moved " + String.format("%.1f", score.confidenceDelta())
                            + " points toward the majority while its reasoning did not"
                            + " (self-similarity " + String.format("%.2f", score.textSimilarity())
                            + ", alignment to others "
                            + String.format("%.2f", score.alignmentToOthers()) + ")";
                    ctx.addSycophancyWarning(warning);
                    events.publish(ctx.session().id(), stage().name(),
                            "DEBATE_SYCOPHANCY_WARNING", score.modelId(),
                            Map.of("confidenceDelta", score.confidenceDelta(),
                                   "textSimilarity", score.textSimilarity(),
                                   "alignmentToOthers", score.alignmentToOthers(),
                                   "similarityThreshold", similarityThreshold,
                                   "confidenceDeltaThreshold", sycophancyDelta));
                }
            }

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
            } else if (convergence.hasConverged(prevRound, debateRound)) {
                events.publish(ctx.session().id(), stage().name(), "DEBATE_CONVERGED", null,
                               Map.of("round", round,
                                      "criterion", debateRound.contributions().size()
                                                   >= DebateConvergenceDetector.KS_MINIMUM_SAMPLE
                                                   ? "ks-distance" : "confidence-delta",
                                      "ksThreshold", ksThreshold,
                                      "confidenceDelta", confidenceDelta,
                                      "minRounds", minRounds));
                break;
            }
            prevRound = debateRound;
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
        ModelProfile model = ctx.executionRegistry(registry).model(modelId);
        try {
            // Use role-aware debate prompt so CRITIC models
            // challenge consensus and SYNTHESIZER models seek common ground.
            PromptBudget budget = PromptBudget.forModel(model);
            List<ChatMessage> messages = promptBuilder.debateMessagesForRole(
                    ctx.session().question(), ctx.session().context(), ctx.drafts(),
                    ctx.debateRounds(), round, model.councilRole(), budget);
            PromptBudgets.record(ctx, events, stage(), modelId, budget);

            ModelCallResult result = ModelCallDeadline.call(ctx.executionRegistry(registry).clientForModel(modelId),
                    new ModelCallRequest(ctx.session().id(), stage(), model.id(),
                                         model.providerModelId(), messages,
                                         model.defaultOutputTokens(), model.temperature(), false, model.defaultTimeout()), model);
            ctx.recordUsage(model.id(), stage(), result.promptTokens(), result.completionTokens(), result.latency());

            TrustBoundaryGuard.Assessment trust = TrustBoundaryGuard.assess(
                    ctx.session().context(), result.text());
            if (trust.violated()) {
                String reason = "Debate contribution from " + modelId + " was excluded: " + trust.reason();
                ctx.excludeModel(modelId, reason);
                ctx.markDegraded(reason);
                events.publish(ctx.session().id(), stage().name(),
                        "DEBATE_TRUST_BOUNDARY_REJECTED", modelId,
                        Map.of("reason", trust.reason(), "matchedTerms", trust.matchedLiterals()));
                return null;
            }

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
     * Parse a free-text confidence declaration into the 0–100 scale.
     *
     * <p>The prompts ask every model to end its contribution with
     * {@code Confidence: NN}, so when several matches appear the <em>last</em>
     * one wins: prose earlier in the argument ("confidence level 3 of the
     * cited study") must not outrank the model's own closing declaration.
     *
     * <p>Scale is decided by normalisation rather than by which pattern matched:
     * <ul>
     *   <li>an explicit {@code %} is already 0–100;</li>
     *   <li>a decimal at or below 1.0 is the 0.0–1.0 scale and is multiplied up;</li>
     *   <li>a decimal above 1.0 is already 0–100;</li>
     *   <li>a bare integer in 2–100 is already 0–100.</li>
     * </ul>
     *
     * <p>A bare {@code 0} or {@code 1} is <b>rejected as ambiguous</b> rather
     * than guessed: on one scale a bare 1 means near-certainty and on the other
     * it means near-zero, and guessing between them is what the previous
     * implementation did wrong. Values outside 0–100 are rejected for the same
     * reason — a clamp presents a number the model never stated.
     *
     * @param text The full debate contribution text from a model.
     * @return The parsed confidence value (0–100), or empty when absent or ambiguous.
     */
    static OptionalInt parseConfidence(String text) {
        if (text == null || text.isBlank()) {
            return OptionalInt.empty();
        }
        Matcher m = CONFIDENCE_PATTERN.matcher(text);
        OptionalInt last = OptionalInt.empty();
        while (m.find()) {
            OptionalInt candidate = normalise(m.group(1), "%".equals(m.group(2)));
            if (candidate.isPresent()) {
                last = candidate;
            }
        }
        return last;
    }

    /**
     * Convert one captured numeric token to the 0–100 scale.
     *
     * @param token   the raw numeric text, e.g. {@code "0.85"}, {@code ".7"}, {@code "85"}
     * @param percent whether the token was followed by a {@code %} sign
     * @return the normalised value, or empty when the token is ambiguous or out of range
     */
    private static OptionalInt normalise(String token, boolean percent) {
        double value;
        try {
            value = Double.parseDouble(token);
        } catch (NumberFormatException ex) {
            return OptionalInt.empty();
        }
        boolean decimal = token.contains(".");

        if (percent || decimal) {
            // "0.85" on the 0.0–1.0 scale; "85.5%" and "8.5" already on 0–100.
            double scaled = (decimal && value <= 1.0) ? value * 100.0 : value;
            return inRange(Math.round(scaled));
        }
        long integral = (long) value;
        if (integral == 0 || integral == 1) {
            // Unrecoverably either "1%" or "100%". Report nothing rather than a guess.
            return OptionalInt.empty();
        }
        return inRange(integral);
    }

    /** Accept a normalised value only if it is a confidence at all. */
    private static OptionalInt inRange(long value) {
        return (value < 0 || value > 100) ? OptionalInt.empty() : OptionalInt.of((int) value);
    }
}
