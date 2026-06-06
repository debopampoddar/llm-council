/**
 * Auto-generated documentation for ExportManifest.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.export;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExportManifest(UUID sessionId,
                             String profileId,
                             String protocolId,
                             Instant createdAt,
                             List<String> modelIds,
                             List<String> includedArtifacts,
                             List<String> excludedArtifacts,
                             boolean rawArtifactsIncluded,
                             String redactionPolicy,
                             String exportPath) {
}
