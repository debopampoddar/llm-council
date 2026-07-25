package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.advisor.CouncilRequirement;
import com.debopam.llmcouncil.advisor.SynthesisResult;
import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Request and response bodies for the requirement advisor.
 *
 * <p>Grouped in one file because they are one conversation: describe, review,
 * propose, save. Splitting four small records across four files would spread a
 * single exchange over four places to look.
 */
public final class AdvisorRequests {

    private AdvisorRequests() {
    }

    /**
     * Ask a model to read a description.
     *
     * <p>{@code modelId} is an id from
     * {@link AdvisorEnvironmentResponse#extractionModels()} and is matched
     * exactly against that list. It is never free text and never interpreted, so
     * no description can redirect extraction at a model the user was not
     * offered.
     *
     * @param text                     the description; treated as data throughout
     * @param modelId                  which model to ask
     * @param acknowledgeCloudProvider confirmation that sending the description
     *                                 off this machine is acceptable; required
     *                                 before any non-local model is used
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ExtractRequest(String text, String modelId, Boolean acknowledgeCloudProvider) {

        /** @return whether the caller confirmed sending the description onward */
        public boolean acknowledged() {
            return Boolean.TRUE.equals(acknowledgeCloudProvider);
        }
    }

    /**
     * Turn a requirement into configuration, without saving anything.
     *
     * @param requirement   what the user wants
     * @param shadowDefault whether the council should also become the profile an
     *                      unqualified request runs
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SynthesizeRequest(CouncilRequirement requirement, Boolean shadowDefault) {

        /** @return the requirement, defaulted when the body omitted it */
        public CouncilRequirement requirementOrDefaults() {
            return requirement == null ? CouncilRequirement.defaults() : requirement;
        }

        /** @return whether the built-in default profile should be shadowed too */
        public boolean shadow() {
            return Boolean.TRUE.equals(shadowDefault);
        }
    }

    /**
     * What the advisor produced, with its checks attached.
     *
     * <p>One response rather than three round trips, for the reason the catalog
     * is one endpoint and not six: a document, the validation of that document,
     * and the diff it would produce have to describe the same configuration. Read
     * separately they could straddle a change and disagree, and the user would be
     * approving a preview of something else.
     *
     * @param profileId  the profile a user would select, or null when no council
     *                   could be produced
     * @param document   the configuration that would be saved
     * @param rationale  one sentence per decision
     * @param issues     what the user needs to know, including why nothing was
     *                   produced when nothing was
     * @param validation what validating the document finds
     * @param preview    what applying it would change
     */
    public record SynthesizeResponse(
            String profileId,
            UserConfigDocument document,
            List<String> rationale,
            List<ConfigIssue> issues,
            ValidationReportResponse validation,
            CatalogDiffResponse preview
    ) {

        /**
         * Assemble a response from a synthesis and its checks.
         *
         * @param result     what the advisor produced
         * @param validation what validating it found
         * @param preview    what applying it would change
         * @return the response
         */
        public static SynthesizeResponse of(SynthesisResult result,
                                            ValidationReportResponse validation,
                                            CatalogDiffResponse preview) {
            return new SynthesizeResponse(result.profileId(), result.document(), result.rationale(),
                                          result.issues(), validation, preview);
        }
    }

    /**
     * Save a council for later.
     *
     * <p>Carries a requirement and not a document, deliberately. There is
     * therefore no path by which a hand-assembled configuration enters the
     * proposal store: intent goes in, and what comes out is what this
     * application derived from it.
     *
     * @param requirement   what the user wants
     * @param shadowDefault whether the council should also become the default
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SaveProposalRequest(CouncilRequirement requirement, Boolean shadowDefault) {

        /** @return the requirement, defaulted when the body omitted it */
        public CouncilRequirement requirementOrDefaults() {
            return requirement == null ? CouncilRequirement.defaults() : requirement;
        }

        /** @return whether the built-in default profile should be shadowed too */
        public boolean shadow() {
            return Boolean.TRUE.equals(shadowDefault);
        }
    }

    /**
     * A request the advisor declined.
     *
     * @param message     what is wrong, phrased for the person who asked
     * @param remediation what to do about it, or null when the message says it
     */
    public record AdvisorError(String message, String remediation) {}
}
