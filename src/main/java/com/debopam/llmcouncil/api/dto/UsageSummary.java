package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.orchestration.StageType;
import com.debopam.llmcouncil.orchestration.UsageRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a run consumed, broken down by model and by stage.
 *
 * <p>A {@code multi-cloud-rigorous} run is thirty to forty cloud calls, and
 * until this existed the answer arrived with no indication of what it cost. The
 * point is preventing surprise, not billing.
 *
 * <p>Two conventions carry the honesty of the numbers, and both are load-bearing:
 *
 * <ul>
 *   <li>{@code estimated} is set when any provider omitted token counts. Those
 *       calls contribute zero, so a total carrying this flag is a floor, not a
 *       measurement.</li>
 *   <li>{@code estimatedCostUsd} is <b>null</b>, never zero, when nothing that
 *       ran carried a price. Zero would render as {@code $0.00} and make an
 *       unpriced cloud model — the default state of every model in this
 *       application — look free. Renderers must show "—" for a null.</li>
 * </ul>
 *
 * @param promptTokens     total input tokens reported across the run
 * @param completionTokens total output tokens reported across the run
 * @param totalTokens      prompt plus completion
 * @param calls            how many model calls the run made
 * @param estimatedCostUsd cost of the priced calls, or null when none were priced
 * @param estimated        true when some provider reported no usage for a call
 * @param partiallyPriced  true when some calls were priced and others were not,
 *                         so the cost covers only part of the run
 * @param byModel          per-model breakdown, most tokens first
 * @param byStage          per-stage breakdown in protocol stage order
 */
public record UsageSummary(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        int calls,
        Double estimatedCostUsd,
        boolean estimated,
        boolean partiallyPriced,
        List<ModelUsage> byModel,
        List<StageUsage> byStage
) {

    /** Cost is money: six decimal places, so a sub-cent call does not round away to nothing. */
    private static final int COST_SCALE = 6;

    /**
     * Aggregate the usage a context accumulated.
     *
     * <p>Prices come from the run's own catalog snapshot, so re-pricing a model
     * mid-run cannot retroactively change what a finished run reports. A context
     * with no catalog binding still reports tokens; it just cannot price them.
     *
     * @param ctx the completed council context
     * @return the summary, or a zero summary when no call was recorded
     */
    public static UsageSummary from(CouncilContext ctx) {
        List<UsageRecord> records = ctx.usage();
        Map<String, ModelProfile> models = pricesFrom(ctx);

        Map<String, Bucket> byModel = new LinkedHashMap<>();
        Map<StageType, Bucket> byStage = new LinkedHashMap<>();
        Bucket total = new Bucket();

        for (UsageRecord record : records) {
            ModelProfile model = models.get(record.modelId());
            byModel.computeIfAbsent(record.modelId(), ignored -> new Bucket()).add(record, model);
            byStage.computeIfAbsent(record.stage(), ignored -> new Bucket()).add(record, model);
            total.add(record, model);
        }

        List<ModelUsage> modelUsages = byModel.entrySet().stream()
                .map(entry -> entry.getValue().toModelUsage(entry.getKey()))
                .sorted(Comparator.comparingLong(ModelUsage::totalTokens).reversed()
                                  .thenComparing(ModelUsage::modelId))
                .toList();

        // Stage order follows the protocol, not observed order: a reader
        // comparing two runs of the same protocol should see the same rows in
        // the same places even when a stage was skipped.
        List<StageUsage> stageUsages = new ArrayList<>();
        for (StageType stage : ctx.protocol().orderedStages()) {
            Bucket bucket = byStage.remove(stage);
            if (bucket != null) {
                stageUsages.add(bucket.toStageUsage(stage));
            }
        }
        byStage.forEach((stage, bucket) -> stageUsages.add(bucket.toStageUsage(stage)));

        return new UsageSummary(
                total.promptTokens,
                total.completionTokens,
                total.promptTokens + total.completionTokens,
                records.size(),
                total.cost(),
                total.estimated,
                total.partiallyPriced(),
                modelUsages,
                List.copyOf(stageUsages));
    }

    /**
     * The price list this run was pinned to.
     *
     * @param ctx the council context
     * @return model id to profile, empty when the context has no catalog binding
     */
    private static Map<String, ModelProfile> pricesFrom(CouncilContext ctx) {
        if (ctx.catalog() == null) {
            return Map.of();
        }
        Map<String, ModelProfile> models = new LinkedHashMap<>();
        var registry = ctx.modelRegistry();
        for (String id : registry.modelIds()) {
            models.put(id, registry.model(id));
        }
        return models;
    }

    /**
     * One model's share of the run.
     *
     * @param modelId          the logical model id
     * @param calls            how many times it was called
     * @param promptTokens     input tokens it consumed
     * @param completionTokens output tokens it produced
     * @param totalTokens      prompt plus completion
     * @param estimatedCostUsd its cost, or null when the model is unpriced
     * @param estimated        true when a call by this model reported no usage
     */
    public record ModelUsage(
            String modelId,
            int calls,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            Double estimatedCostUsd,
            boolean estimated
    ) {}

    /**
     * One stage's share of the run.
     *
     * @param stage            the stage name
     * @param calls            how many model calls the stage made
     * @param promptTokens     input tokens the stage consumed
     * @param completionTokens output tokens the stage produced
     * @param totalTokens      prompt plus completion
     * @param estimatedCostUsd the stage's cost, or null when nothing in it was priced
     * @param estimated        true when a call in this stage reported no usage
     */
    public record StageUsage(
            String stage,
            int calls,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            Double estimatedCostUsd,
            boolean estimated
    ) {}

    /** Mutable accumulator; never escapes this class. */
    private static final class Bucket {
        private int calls;
        private long promptTokens;
        private long completionTokens;
        private double cost;
        private boolean estimated;
        private boolean sawPriced;
        private boolean sawUnpriced;

        void add(UsageRecord record, ModelProfile model) {
            calls++;
            promptTokens += record.promptTokensOrZero();
            completionTokens += record.completionTokensOrZero();
            estimated |= record.incomplete();
            if (model != null && model.priced()) {
                sawPriced = true;
                cost += record.promptTokensOrZero() / 1000.0 * model.costPer1kInputTokens()
                        + record.completionTokensOrZero() / 1000.0 * model.costPer1kOutputTokens();
            } else {
                sawUnpriced = true;
            }
        }

        /** @return the accrued cost, or null when nothing in this bucket had a price */
        Double cost() {
            if (!sawPriced) {
                return null;
            }
            double scale = Math.pow(10, COST_SCALE);
            return Math.round(cost * scale) / scale;
        }

        boolean partiallyPriced() {
            return sawPriced && sawUnpriced;
        }

        ModelUsage toModelUsage(String modelId) {
            return new ModelUsage(modelId, calls, promptTokens, completionTokens,
                                  promptTokens + completionTokens, cost(), estimated);
        }

        StageUsage toStageUsage(StageType stage) {
            return new StageUsage(stage.name(), calls, promptTokens, completionTokens,
                                  promptTokens + completionTokens, cost(), estimated);
        }
    }
}
