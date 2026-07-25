package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.config.ConfigIssue;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What validating a proposed configuration found.
 *
 * <p>Errors and warnings are both returned, and both matter, but they mean
 * different things. An error means the entity would be dropped, so the overlay
 * is refused as a whole rather than saved half-applied. A warning means the
 * entity is usable and something about it is worth knowing — most often that it
 * weakens a guarantee the council exists to provide.
 *
 * <p>{@code integrityReduced} is reported separately from the warning list
 * because it is not a lint. A configuration with dissent preservation switched
 * off produces answers that read as more confident than the council actually
 * was, and that has to survive the trip from this response into whatever renders
 * it, rather than being one warning among several.
 *
 * @param valid            whether the configuration has no errors and could be saved
 * @param errorCount       issues that would drop an entity
 * @param warningCount     issues that would not
 * @param integrityReduced whether any anti-sycophancy guarantee is weakened by
 *                         this configuration
 * @param issues           every issue found, errors and warnings alike
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationReportResponse(
        boolean valid,
        int errorCount,
        int warningCount,
        boolean integrityReduced,
        List<ConfigIssue> issues
) {

    /**
     * Build a report from a list of issues.
     *
     * @param issues           every issue found
     * @param integrityReduced whether the configuration weakens a guarantee
     * @return the report, with the counts derived rather than passed in
     */
    public static ValidationReportResponse of(List<ConfigIssue> issues, boolean integrityReduced) {
        int errors = (int) issues.stream()
                                 .filter(issue -> issue.severity() == ConfigIssue.Severity.ERROR)
                                 .count();
        return new ValidationReportResponse(errors == 0, errors, issues.size() - errors,
                                            integrityReduced, List.copyOf(issues));
    }
}
