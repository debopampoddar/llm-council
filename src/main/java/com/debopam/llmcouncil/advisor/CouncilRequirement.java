package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.config.user.ConfigLimits;
import com.debopam.llmcouncil.domain.DepthMode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a user wants from a council, expressed as closed choices.
 *
 * <p>This is the <b>only</b> thing a language model is allowed to produce in the
 * advisor. It carries no ids, no provider names, and no stage types, and it
 * cannot be extended to carry them without editing this file — which is what
 * makes the guarantee structural rather than a matter of prompt wording. A model
 * that tries to name {@code gpt-4o} has nowhere to put it, so
 * {@link ConfigSynthesizer} can never be steered by free text.
 *
 * <p>Every field is normalised in the compact constructor, so a requirement that
 * arrives from a model, a form, or a hand-written request body is in range before
 * anything reads it.
 *
 * @param privacy             where the models may run
 * @param latency             how long the user is willing to wait
 * @param cost                what they are willing to spend
 * @param rigor               how carefully the council should deliberate
 * @param councilSize         how many members to seat, 1 to
 *                            {@link ConfigLimits#MAX_MEMBERS}
 * @param domains             what the council is for; see {@link Domain} for why
 *                            this does not affect model selection
 * @param adversarialEmphasis whether to weight the council towards critics
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CouncilRequirement(
        Privacy privacy,
        Latency latency,
        Cost cost,
        Rigor rigor,
        int councilSize,
        Set<Domain> domains,
        boolean adversarialEmphasis
) {

    /**
     * Seats used when a requirement does not say.
     *
     * <p>Three, because it is the smallest council that can hold a majority
     * opinion and a dissenting one at the same time — below it, peer review has
     * nobody to disagree with.
     */
    public static final int DEFAULT_COUNCIL_SIZE = 3;

    /**
     * Normalises every field so downstream code never guards against nulls or
     * out-of-range sizes.
     *
     * <p>A {@code councilSize} of zero or less is read as <em>unspecified</em>
     * rather than clamped up to one. Jackson binds an absent JSON field to zero
     * on a primitive {@code int}, and a model that simply omitted the field has
     * not asked for a one-member council — it has said nothing, and the default
     * is the honest reading.
     */
    public CouncilRequirement {
        privacy = privacy == null ? Privacy.PREFER_LOCAL : privacy;
        latency = latency == null ? Latency.MODERATE : latency;
        cost = cost == null ? Cost.LOW : cost;
        rigor = rigor == null ? Rigor.BALANCED : rigor;
        councilSize = councilSize <= 0
                      ? DEFAULT_COUNCIL_SIZE
                      : Math.min(councilSize, ConfigLimits.MAX_MEMBERS);
        domains = domains == null || domains.isEmpty()
                  ? Set.of(Domain.GENERAL)
                  : Set.copyOf(new LinkedHashSet<>(domains));
    }

    /**
     * The requirement assumed when a user skips every question.
     *
     * @return a moderate, prefer-local, balanced council of three
     */
    public static CouncilRequirement defaults() {
        return new CouncilRequirement(Privacy.PREFER_LOCAL, Latency.MODERATE, Cost.LOW,
                                      Rigor.BALANCED, DEFAULT_COUNCIL_SIZE,
                                      Set.of(Domain.GENERAL), false);
    }

    /**
     * Whether this requirement rules out anything that is not run locally.
     *
     * <p>{@link Cost#FREE_ONLY} counts, and that is not a shortcut. A model's
     * configured price of zero means <em>unpriced</em>, not free — every shipped
     * cloud model carries zero because nobody set a figure — so filtering on the
     * price field would seat an unpriced cloud model on a council the user asked
     * to keep free. Free means local.
     *
     * @return {@code true} when only locally-run models are acceptable
     */
    @JsonIgnore
    public boolean localOnly() {
        return privacy == Privacy.LOCAL_ONLY || cost == Cost.FREE_ONLY;
    }

    /**
     * The depth this requirement's rigor selects by default.
     *
     * <p>Only the default: a synthesised profile always maps all three depths, so
     * describing a quick council never removes the careful one.
     *
     * @return the matching depth mode
     */
    @JsonIgnore
    public DepthMode defaultDepth() {
        return switch (rigor) {
            case QUICK -> DepthMode.QUICK;
            case BALANCED -> DepthMode.BALANCED;
            case RIGOROUS -> DepthMode.RIGOROUS;
        };
    }

    /** Where the models backing a council may run. */
    public enum Privacy {
        /** Nothing leaves the machine. Only locally-run models are considered. */
        LOCAL_ONLY,

        /** Local models are preferred; cloud models fill gaps in size or diversity. */
        PREFER_LOCAL,

        /** Any configured provider is acceptable. */
        CLOUD_OK
    }

    /** How long the user is willing to wait for an answer. */
    public enum Latency {
        /** Wants an answer soon; caps council size and shortens debate. */
        FAST,

        /** No particular urgency. */
        MODERATE,

        /** Willing to wait for the most careful answer available. */
        PATIENT
    }

    /** What the user is willing to spend per run. */
    public enum Cost {
        /** Nothing. Local models only — see {@link CouncilRequirement#localOnly()}. */
        FREE_ONLY,

        /** Some spend is acceptable; local models are still preferred. */
        LOW,

        /** Cost is not a consideration. */
        UNCONSTRAINED
    }

    /** How carefully the council should deliberate. */
    public enum Rigor {
        /** Draft and synthesise. No review, no debate. */
        QUICK,

        /** Anonymised review, scoring, and Fresh Eyes validation. */
        BALANCED,

        /** Adds debate, revision, re-review, and a second scoring pass. */
        RIGOROUS
    }

    /**
     * What the council is for.
     *
     * <p>This <b>does not affect model selection</b>, and the advisor says so in
     * its rationale rather than leaving a user to infer it. Choosing models by
     * domain would need per-model capability data, and this application records
     * none — a mapping from "you said CODE" to "so I picked this model" would be
     * invention presented as inference, which is the failure mode the trust
     * signals exist to prevent. The field is kept because naming the intent is
     * worth something on its own, and it appears in the profile's display name.
     */
    public enum Domain {
        /** Writing, reviewing, or reasoning about code. */
        CODE,

        /** Drafting or editing prose. */
        WRITING,

        /** Weighing evidence and reaching a judgement. */
        ANALYSIS,

        /** Gathering and summarising material. */
        RESEARCH,

        /** No particular specialism. */
        GENERAL
    }
}
