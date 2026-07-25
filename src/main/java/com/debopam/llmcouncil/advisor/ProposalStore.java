package com.debopam.llmcouncil.advisor;

import java.util.Optional;

/**
 * Where an unapplied proposal is kept.
 *
 * <p>An interface with one implementation, on purpose. Whether a proposal
 * belongs to an installation or to a user is a question this application has not
 * answered — there is no authentication, so today it belongs to the
 * installation, and a file is the honest expression of that. If multi-user is
 * ever answered differently the answer is a second implementation and a passing
 * contract test, not an excavation.
 *
 * <p>Exactly one proposal exists at a time. Saving replaces; there is no list,
 * no id, and no history, because a proposal is a thing you are in the middle of
 * rather than a record of what you did.
 */
public interface ProposalStore {

    /**
     * Read the saved proposal.
     *
     * <p>Never called during startup. A proposal is not configuration, and the
     * only thing standing between those two ideas is that nothing reads this
     * except a user asking for it.
     *
     * @return the proposal, or empty when there is none or it is unreadable
     */
    Optional<ProposalEnvelope> load();

    /**
     * Save a proposal, replacing any previous one.
     *
     * @param proposal what to save
     * @return where it was written, for showing the user
     */
    String save(ProposalEnvelope proposal);

    /**
     * Remove the saved proposal.
     *
     * <p>A discard leaves nothing behind. This store keeps no backup copy for
     * exactly that reason: a discard that left a file next to the one it deleted
     * would not be a discard.
     *
     * @return {@code true} when something was removed
     */
    boolean discard();

    /**
     * Where proposals live, whether or not one exists.
     *
     * @return a displayable location
     */
    String location();
}
