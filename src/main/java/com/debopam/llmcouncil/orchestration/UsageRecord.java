package com.debopam.llmcouncil.orchestration;

import java.time.Duration;

/**
 * What one model call consumed.
 *
 * <p>One record per {@code .call(} site, written the moment the call returns.
 * Token fields are nullable because {@link com.debopam.llmcouncil.model.ModelCallResult}
 * declares them nullable: not every provider reports usage, and a provider that
 * omits it must be visibly distinguishable from one that reported zero. A null
 * therefore contributes zero to a total and marks the total as an estimate,
 * rather than being silently coerced to 0 and reported as fact.
 *
 * @param modelId          the logical model id that was called
 * @param stage            the protocol stage the call belonged to
 * @param promptTokens     input tokens the provider reported, or null if it reported none
 * @param completionTokens output tokens the provider reported, or null if it reported none
 * @param latency          wall-clock time for the call, or null when the adapter did not measure it
 */
public record UsageRecord(
        String modelId,
        StageType stage,
        Long promptTokens,
        Long completionTokens,
        Duration latency
) {

    /** @return {@code true} when the provider omitted either token count for this call */
    public boolean incomplete() {
        return promptTokens == null || completionTokens == null;
    }

    /** @return reported prompt tokens, or zero when the provider omitted them */
    public long promptTokensOrZero() {
        return promptTokens == null ? 0L : promptTokens;
    }

    /** @return reported completion tokens, or zero when the provider omitted them */
    public long completionTokensOrZero() {
        return completionTokens == null ? 0L : completionTokens;
    }
}
