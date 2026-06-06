/**
 * Auto-generated documentation for StageExecutorRegistry.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class StageExecutorRegistry {
    private final Map<StageType, StageExecutor> executors;

    public StageExecutorRegistry(List<StageExecutor> stageExecutors) {
        EnumMap<StageType, StageExecutor> byStage = new EnumMap<>(StageType.class);
        for (StageExecutor executor : stageExecutors) {
            StageExecutor previous = byStage.put(executor.stage(), executor);
            if (previous != null) {
                throw new IllegalStateException("Duplicate stage executor for " + executor.stage());
            }
        }
        this.executors = Map.copyOf(byStage);
    }

    public StageExecutor get(StageType stageType) {
        StageExecutor executor = executors.get(stageType);
        if (executor == null) {
            throw new IllegalArgumentException("No executor registered for stage " + stageType);
        }
        return executor;
    }
}
