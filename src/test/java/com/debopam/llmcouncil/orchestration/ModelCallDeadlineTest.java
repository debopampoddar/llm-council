package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.config.TestModels;
import com.debopam.llmcouncil.model.ChatMessage;
import com.debopam.llmcouncil.model.ModelCallException;
import com.debopam.llmcouncil.model.ModelCallRequest;
import com.debopam.llmcouncil.model.ModelFailureCategory;
import com.debopam.llmcouncil.model.ModelRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCallDeadlineTest {

    @Test
    void returnsTypedTimeoutWhenAClientDoesNotFinishBeforeTheDeadline() {
        var model = TestModels.model("slow").provider("anthropic").providerModelId("slow-model")
                .role(ModelRole.MEMBER).timeout(Duration.ofMillis(30)).build();
        ModelCallRequest request = new ModelCallRequest("session", StageType.GENERATE, model.id(),
                model.providerModelId(), List.of(new ChatMessage("user", "hello")),
                64, 1.0, false, Duration.ofMillis(30));
        long started = System.nanoTime();

        ModelCallException failure = assertThrows(ModelCallException.class, () ->
                ModelCallDeadline.call(ignored -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ignoredInterrupt) {
                        // A provider SDK may receive cancellation too late; the caller must still return.
                    }
                    return new com.debopam.llmcouncil.model.ModelCallResult("late");
                }, request, model));

        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertEquals(ModelFailureCategory.MODEL_TIMEOUT, failure.category());
        assertTrue(elapsedMs < 1_000, "hard deadline was ignored; elapsed=" + elapsedMs);
    }
}
