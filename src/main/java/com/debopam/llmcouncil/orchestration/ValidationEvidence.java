package com.debopam.llmcouncil.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts untrusted validator JSON into internally consistent evidence. */
final class ValidationEvidence {

    private static final List<String> REQUIRED_CRITERIA = List.of(
            "correctness", "completeness", "uncertainty", "safety", "actionability");
    private static final Set<String> VERDICTS = Set.of("pass", "warn", "fail");

    private ValidationEvidence() {
    }

    static ValidationArtifact normalize(String validatorId,
                                        StructuredOutputParser.ValidationEnvelope parsed,
                                        String rawText) {
        Map<String, String> criteria = new LinkedHashMap<>();
        if (parsed.criteria() != null) {
            parsed.criteria().forEach((key, value) -> {
                if (key != null && value != null) {
                    criteria.put(key, value);
                }
            });
        }
        List<String> issues = clean(parsed.issues());
        List<String> recommendedFixes = clean(parsed.recommendedFixes());

        boolean criteriaComplete = true;
        boolean criterionFailed = false;
        for (String criterion : REQUIRED_CRITERIA) {
            String assessment = criteria.get(criterion);
            String verdict = verdict(assessment);
            if (verdict == null) {
                criteriaComplete = false;
                issues.add("Validator did not provide a valid " + criterion
                        + " verdict (expected pass, warn, or fail plus a reason).");
            } else if ("fail".equals(verdict)) {
                criterionFailed = true;
            }
        }

        if (parsed.requiresHumanReview()) {
            issues.add("Material correctness could not be established by the model validator; human review is required.");
        }
        if (parsed.approved() && criterionFailed) {
            issues.add("Validator approval contradicted at least one failed criterion; approval was overridden.");
        }

        boolean approved = parsed.approved()
                && criteriaComplete
                && !criterionFailed
                && !parsed.requiresHumanReview();
        return new ValidationArtifact(
                validatorId,
                approved,
                parsed.confidence(),
                issues,
                recommendedFixes,
                criteria,
                parsed.requiresHumanReview(),
                rawText);
    }

    private static String verdict(String assessment) {
        if (assessment == null || assessment.isBlank()) {
            return null;
        }
        String normalized = assessment.strip().toLowerCase(Locale.ROOT);
        int separator = 0;
        while (separator < normalized.length()
                && Character.isLetter(normalized.charAt(separator))) {
            separator++;
        }
        String candidate = normalized.substring(0, separator);
        return VERDICTS.contains(candidate) ? candidate : null;
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList());
    }
}
