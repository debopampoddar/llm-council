package com.debopam.llmcouncil.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationEvidenceTest {

    private static final Map<String, String> PASSING = Map.of(
            "correctness", "pass: independently checked",
            "completeness", "pass: covers the request",
            "uncertainty", "warn: minor limitation is disclosed",
            "safety", "pass: no unsafe instruction",
            "actionability", "pass: steps are usable");

    @Test
    void acceptsCompleteConsistentEvidenceWithNonBlockingWarning() {
        ValidationArtifact artifact = normalize(true, PASSING, false);

        assertTrue(artifact.approved());
    }

    @Test
    void overridesApprovalWhenARequiredCriterionFailed() {
        Map<String, String> criteria = new java.util.LinkedHashMap<>(PASSING);
        criteria.put("correctness", "fail: arithmetic is wrong");

        ValidationArtifact artifact = normalize(true, criteria, false);

        assertFalse(artifact.approved());
        assertTrue(artifact.issues().stream().anyMatch(issue -> issue.contains("contradicted")));
    }

    @Test
    void overridesApprovalWhenHumanReviewIsRequired() {
        ValidationArtifact artifact = normalize(true, PASSING, true);

        assertFalse(artifact.approved());
        assertTrue(artifact.requiresHumanReview());
        assertTrue(artifact.issues().stream().anyMatch(issue -> issue.contains("human review")));
    }

    @Test
    void failsClosedWhenARequiredCriterionIsMissingOrMalformed() {
        ValidationArtifact missing = normalize(true, Map.of("correctness", "pass: checked"), false);
        Map<String, String> malformedCriteria = new java.util.LinkedHashMap<>(PASSING);
        malformedCriteria.put("safety", "looks good");
        ValidationArtifact malformed = normalize(true, malformedCriteria, false);

        assertFalse(missing.approved());
        assertFalse(malformed.approved());
        assertTrue(malformed.issues().stream().anyMatch(issue -> issue.contains("safety")));
    }

    @Test
    void nullValuesInUntrustedValidatorCollectionsBecomeFailedEvidenceNotAnException() {
        Map<String, String> criteria = new java.util.HashMap<>(PASSING);
        criteria.put("correctness", null);
        StructuredOutputParser.ValidationEnvelope envelope =
                new StructuredOutputParser.ValidationEnvelope(
                        true, 0.8, java.util.Arrays.asList(null, "real issue"),
                        java.util.Arrays.asList("fix", null), criteria, false);

        ValidationArtifact artifact = ValidationEvidence.normalize("validator", envelope, "raw");

        assertFalse(artifact.approved());
        assertTrue(artifact.issues().contains("real issue"));
        assertTrue(artifact.recommendedFixes().contains("fix"));
    }

    private static ValidationArtifact normalize(boolean approved, Map<String, String> criteria,
                                                boolean humanReview) {
        return ValidationEvidence.normalize("validator",
                new StructuredOutputParser.ValidationEnvelope(
                        approved, 0.8, List.of(), List.of(), criteria, humanReview),
                "raw");
    }
}
