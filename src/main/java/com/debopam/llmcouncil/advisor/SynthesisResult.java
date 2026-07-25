package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * What the advisor produced, and why.
 *
 * <p>The document is <b>always</b> present, even when synthesis failed. On
 * failure it is the user's existing configuration, unchanged — because the
 * advisor is additive, and an outcome that could not add anything has to leave
 * what is there alone rather than return an empty document that would read as
 * "delete everything you had".
 *
 * <p>{@link #profileId} is the honest success signal: it is null exactly when no
 * council was synthesised, so a caller cannot mistake "your existing config,
 * returned unchanged" for "here is your new council".
 *
 * @param document   the configuration to save; the input document when synthesis
 *                   could not produce a council
 * @param profileId  the profile a user would select, or null when none was made
 * @param rationale  one plain sentence per decision, in the order decided
 * @param issues     what the user needs to know, including why nothing was
 *                   produced when nothing was
 */
public record SynthesisResult(
        UserConfigDocument document,
        String profileId,
        List<String> rationale,
        List<ConfigIssue> issues
) {

    /** Defensive copies, so a finished result cannot grow a reason after the fact. */
    public SynthesisResult {
        rationale = rationale == null ? List.of() : List.copyOf(rationale);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /**
     * Whether a council was actually synthesised.
     *
     * @return {@code true} when a profile was produced
     */
    @JsonIgnore
    public boolean successful() {
        return profileId != null;
    }

    /**
     * Whether anything here would stop the configuration being saved.
     *
     * @return {@code true} when at least one issue is an error
     */
    @JsonIgnore
    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.severity() == ConfigIssue.Severity.ERROR);
    }
}
