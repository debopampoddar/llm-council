/**
 * Auto-generated documentation for ProtocolDefinition.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import java.util.List;
import java.util.Map;

public record ProtocolDefinition(
        String id,
        String description,
        List<StageType> orderedStages,
        Map<StageType, ProtocolStageOptions> stageOptions) {
    public ProtocolStageOptions optionsFor(StageType stageType) {
        return stageOptions == null
                ? ProtocolStageOptions.defaults()
                : stageOptions.getOrDefault(stageType, ProtocolStageOptions.defaults());
    }
}
