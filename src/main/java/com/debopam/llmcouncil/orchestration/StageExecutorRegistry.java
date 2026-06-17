package com.debopam.llmcouncil.orchestration;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Auto-discovers all {@link StageExecutor} beans and indexes them by
 * {@link StageType}.
 *
 * <p>Adding a new stage executor requires only annotating it with
 * {@code @Component} — no changes needed here.
 */
@Component
public class StageExecutorRegistry {

    private final Map<StageType, StageExecutor> executors;

    /**
     * Spring injects all {@link StageExecutor} components via the list parameter.
     *
     * @param executors All discovered stage executor beans.
     */
    public StageExecutorRegistry(List<StageExecutor> executors) {
        this.executors = executors.stream()
                                  .collect(Collectors.toUnmodifiableMap(StageExecutor::stage, e -> e));
    }

    /**
     * Look up the executor for a given stage.
     *
     * @param stageType The stage to look up.
     * @return The registered executor.
     * @throws NoSuchElementException if no executor is registered for this stage.
     */
    public StageExecutor get(StageType stageType) {
        StageExecutor e = executors.get(stageType);
        if (e == null) throw new NoSuchElementException(
                "No StageExecutor registered for stage " + stageType
                + ". Registered: " + executors.keySet());
        return e;
    }

    /** @return {@code true} if an executor is registered for this stage type. */
    public boolean has(StageType stageType) {
        return executors.containsKey(stageType);
    }
}
