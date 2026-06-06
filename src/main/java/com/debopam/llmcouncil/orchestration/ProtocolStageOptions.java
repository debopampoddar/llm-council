/**
 * Auto-generated documentation for ProtocolStageOptions.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

public record ProtocolStageOptions(String reviewMode,
                                   Integer maxRounds,
                                   Double debateTriggerScoreVariance,
                                   Integer debateTriggerDissentCount,
                                   Boolean forceRun,
                                   Boolean preserveDissent,
                                   Boolean exportRawArtifacts,
                                   String artifactLabel) {
    public static ProtocolStageOptions defaults() {
        return new ProtocolStageOptions(
                "peer",
                1,
                120.0,
                2,
                false,
                true,
                false,
                null
        );
    }

    public int maxRoundsOrDefault(int fallback) {
        return maxRounds == null ? fallback : maxRounds;
    }

    public double debateTriggerScoreVarianceOrDefault(double fallback) {
        return debateTriggerScoreVariance == null ? fallback : debateTriggerScoreVariance;
    }

    public int debateTriggerDissentCountOrDefault(int fallback) {
        return debateTriggerDissentCount == null ? fallback : debateTriggerDissentCount;
    }

    public boolean forceRunOrDefault(boolean fallback) {
        return forceRun == null ? fallback : forceRun;
    }

    public boolean preserveDissentOrDefault(boolean fallback) {
        return preserveDissent == null ? fallback : preserveDissent;
    }

    public boolean exportRawArtifactsOrDefault(boolean fallback) {
        return exportRawArtifacts == null ? fallback : exportRawArtifacts;
    }

    public String artifactLabelOrDefault(String fallback) {
        return artifactLabel == null || artifactLabel.isBlank() ? fallback : artifactLabel;
    }
}
