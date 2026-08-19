package com.debopam.llmcouncil.model;

import com.debopam.llmcouncil.observability.CouncilMetrics;

import java.time.Duration;

/** Records one metric sample for every physical provider attempt. */
public final class MeteredModelClient implements ModelClient {

    private final ModelClient delegate;
    private final CouncilMetrics metrics;
    private final String provider;

    public MeteredModelClient(ModelClient delegate, CouncilMetrics metrics, String provider) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.provider = provider;
    }

    @Override
    public ModelCallResult call(ModelCallRequest request) throws ModelCallException {
        long started = System.nanoTime();
        try {
            ModelCallResult result = delegate.call(request);
            metrics.modelSucceeded(request, provider, elapsed(started), result);
            return result;
        } catch (ModelCallException ex) {
            metrics.modelFailed(request, provider, elapsed(started), ex.category());
            throw ex;
        } catch (RuntimeException ex) {
            metrics.modelFailed(request, provider, elapsed(started), ModelFailureCategory.UNKNOWN);
            throw ex;
        }
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
