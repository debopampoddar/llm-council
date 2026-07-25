package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * A synthesised council somebody saved without applying it.
 *
 * <p>The wrapper is the point. A proposal is a configuration document, and if it
 * were written as one, a file copied to {@code council-user.yml} would load
 * silently as live configuration — which is exactly the mistake somebody makes
 * at 2am when a council stops working. {@link #kind} makes that impossible
 * through machinery that already exists: {@link UserConfigDocument} binds
 * strictly, so the copied file is refused with <i>"Unrecognized field
 * 'kind'"</i>, reported as a configuration issue, and the application still
 * starts on built-in configuration. A comment at the top of the file would not
 * have survived the copy.
 *
 * <p>The {@link #requirement} is stored alongside the document, not instead of
 * it. Applying uses the document, because that is what the user approved;
 * keeping the requirement is what lets a resumed proposal say whether
 * re-synthesising today would produce something different.
 *
 * @param kind        always {@link #KIND}; the marker that this is not live config
 * @param savedAt     when it was saved, ISO-8601. A string rather than an
 *                    {@link Instant} so the file stays readable and the codec
 *                    needs no date module
 * @param requirement what was asked for
 * @param rationale   why the council came out the way it did
 * @param document    the configuration that would be applied
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ProposalEnvelope(
        String kind,
        String savedAt,
        CouncilRequirement requirement,
        List<String> rationale,
        UserConfigDocument document
) {

    /** The marker distinguishing a proposal from configuration. */
    public static final String KIND = "llm-council-proposal";

    /** Defensive copy of the rationale. */
    public ProposalEnvelope {
        rationale = rationale == null ? List.of() : List.copyOf(rationale);
    }

    /**
     * Wrap a synthesis result for saving.
     *
     * @param requirement what was asked for
     * @param result      what the advisor produced
     * @param savedAt     when it was saved
     * @return the envelope to write
     */
    public static ProposalEnvelope of(CouncilRequirement requirement, SynthesisResult result,
                                      Instant savedAt) {
        return new ProposalEnvelope(KIND, savedAt.toString(), requirement, result.rationale(),
                                    result.document());
    }

    /**
     * Whether this really is a proposal.
     *
     * @return {@code true} when the marker is present and correct
     */
    @JsonIgnore
    public boolean valid() {
        return KIND.equals(kind) && document != null;
    }

    /**
     * When it was saved, as an instant.
     *
     * @return the parsed timestamp, or null when it is missing or unreadable
     */
    @JsonIgnore
    public Instant savedAtInstant() {
        if (savedAt == null || savedAt.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(savedAt);
        } catch (DateTimeParseException ex) {
            // A hand-edited timestamp is not worth refusing the proposal over.
            return null;
        }
    }
}
