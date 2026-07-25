package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.api.dto.CatalogDiffResponse;
import com.debopam.llmcouncil.api.dto.ValidationReportResponse;
import com.debopam.llmcouncil.config.user.UserConfigDocument;

import java.util.List;

/**
 * A saved proposal, checked again at the moment it is read.
 *
 * <p>Validation and the preview are computed on every read rather than stored
 * with the proposal. A proposal written three weeks ago may name an Ollama model
 * that has since been deleted, and offering it for one click of "apply" on the
 * strength of a check that passed three weeks ago is how a user ends up with a
 * council that cannot run.
 *
 * <p><b>Broken and stale are reported separately</b>, because they call for
 * different answers. {@link #validation} catches broken: the document no longer
 * resolves, and applying it would fail. {@link #resynthesisDiffers} catches
 * stale: the document still resolves, but running the advisor again today would
 * pick different models — a new one has been pulled, or an old one is gone.
 * Applying always applies {@link #document}, because that is what the user
 * approved; re-synthesising is a second, explicit choice.
 *
 * @param present            whether a proposal exists at all
 * @param savedAt            when it was saved, ISO-8601, or null when unknown
 * @param location           where it lives, for showing the user
 * @param requirement        what was asked for
 * @param document           the configuration that would be applied
 * @param rationale          why the council came out the way it did
 * @param validation         what validating it finds <em>now</em>
 * @param preview            what applying it would change <em>now</em>
 * @param resynthesisDiffers whether re-running the advisor today would differ
 * @param resynthesisNote    what would differ, in words; null when nothing would
 */
public record StoredProposal(
        boolean present,
        String savedAt,
        String location,
        CouncilRequirement requirement,
        UserConfigDocument document,
        List<String> rationale,
        ValidationReportResponse validation,
        CatalogDiffResponse preview,
        boolean resynthesisDiffers,
        String resynthesisNote
) {

    /** Defensive copy of the rationale. */
    public StoredProposal {
        rationale = rationale == null ? List.of() : List.copyOf(rationale);
    }

    /**
     * The answer when nothing has been saved.
     *
     * @param location where a proposal would live
     * @return an absent proposal
     */
    public static StoredProposal absent(String location) {
        return new StoredProposal(false, null, location, null, null, List.of(), null, null,
                                  false, null);
    }
}
