package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelCallResult;
import com.debopam.llmcouncil.model.ModelClient;
import com.debopam.llmcouncil.model.ModelFailureCategory;
import com.debopam.llmcouncil.model.ModelProfile;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Applies a hard model-call deadline even when a provider client ignores interruption. */
final class ModelCallDeadline {
    private ModelCallDeadline() {}

    static ModelCallResult call(ModelClient client, ModelCallRequest request, ModelProfile model) {
        Duration timeout = request.timeout();
        if (timeout == null) {
            return client.call(request);
        }
        FutureTask<ModelCallResult> task = new FutureTask<>(() -> client.call(request));
        Thread.startVirtualThread(task);
        try {
            return task.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            throw new ModelCallException(ModelFailureCategory.MODEL_TIMEOUT,
                    model.provider(), model.providerModelId(),
                    "Model call exceeded configured timeout " + timeout + ".", ex);
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new ModelCallException(ModelFailureCategory.MODEL_TIMEOUT,
                    model.provider(), model.providerModelId(),
                    "Model call was interrupted while waiting for its configured timeout.", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ModelCallException modelFailure) {
                throw modelFailure;
            }
            throw new ModelCallException(ModelFailureCategory.MODEL_CALL_FAILED,
                    model.provider(), model.providerModelId(),
                    "Model call failed: " + rootMessage(cause), cause);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable == null ? new IllegalStateException("unknown failure") : throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : " - " + message);
    }
}
