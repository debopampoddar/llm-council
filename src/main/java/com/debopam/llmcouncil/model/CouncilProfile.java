/**
 * Auto-generated documentation for CouncilProfile.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.model;

import java.util.List;

public record CouncilProfile(
        String id,
        List<String> memberModelIds,
        String chairModelId,
        String freshEyesModelId,
        String protocolId) {
}
