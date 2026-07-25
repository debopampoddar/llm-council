package com.debopam.llmcouncil.advisor;

import com.debopam.llmcouncil.advisor.AdvisorEnvironment.CandidateModel;
import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.user.ConfigLimits;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserModel;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserPolicy;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserProfile;
import com.debopam.llmcouncil.config.user.UserConfigDocument.UserProtocol;
import com.debopam.llmcouncil.model.ClientAvailability;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.orchestration.StageType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@link CouncilRequirement} into configuration, deterministically.
 *
 * <p><b>This class is pure.</b> No network, no model, no clock, no filesystem —
 * every fact about the machine arrives in {@link AdvisorEnvironment} and every
 * fact about what is already configured arrives in the {@link UserConfigDocument}
 * being extended. That is what makes the interesting half of the advisor
 * testable without a running Ollama, and it is why discovery lives in a separate
 * service rather than being called from here.
 *
 * <p>Two rules shape everything below.
 *
 * <ul>
 *   <li><b>Never propose a model that cannot be called.</b> A model whose
 *       provider has no credential, or an Ollama tag that is not pulled, is
 *       excluded before selection rather than configured and discovered at run
 *       time. This is the single largest quality difference between this and
 *       asking a model to write YAML.</li>
 *   <li><b>Only add.</b> The advisor owns the {@link AdvisorIds#PREFIX}
 *       namespace and replaces its own previous output; every other entity in a
 *       user's overlay is carried through untouched. A wizard that quietly
 *       deleted a hand-written council would be a worse tool than no wizard.</li>
 * </ul>
 */
@Component
public class ConfigSynthesizer {

    /**
     * Seats allowed when the user asked for speed.
     *
     * <p>Council size is the dominant latency term: drafting fans out, but
     * review is quadratic in members and debate is quadratic per round. Capping
     * seats is therefore a real mechanism rather than a guess about what "fast"
     * ought to mean.
     */
    static final int FAST_MAX_COUNCIL_SIZE = 3;

    /** Debate rounds used when a fast, rigorous council is asked for. */
    static final int FAST_DEBATE_ROUNDS = 2;

    /** Quorum fraction from the plan: a council needs most of its drafts. */
    private static final double DRAFT_QUORUM_FRACTION = 0.6;

    // Defaults for models this configuration defines. Chosen to match the
    // shipped local models rather than invented, so a synthesised local council
    // behaves like the hand-written one it stands next to.
    private static final int MEMBER_OUTPUT_TOKENS = 1200;
    private static final int CHAIR_OUTPUT_TOKENS = 1800;
    private static final double MEMBER_TEMPERATURE = 0.3;
    private static final double FOCUSED_TEMPERATURE = 0.2;
    private static final int LOCAL_TIMEOUT_SECONDS = 240;

    /**
     * Synthesise a council, leaving any existing configuration in place.
     *
     * @param requirement what the user asked for
     * @param environment what this machine can run
     * @param existing    the overlay as it is today; null is read as empty
     * @return the configuration to save, the rationale, and anything worth knowing
     */
    public SynthesisResult synthesize(CouncilRequirement requirement,
                                      AdvisorEnvironment environment,
                                      UserConfigDocument existing) {
        return synthesize(requirement, environment, existing, false);
    }

    /**
     * Synthesise a council, optionally pointing the {@code default} profile at it.
     *
     * @param requirement    what the user asked for
     * @param environment    what this machine can run
     * @param existing       the overlay as it is today; null is read as empty
     * @param shadowDefault  whether to also shadow the built-in {@code default}
     *                       profile so an unqualified request runs this council
     * @return the configuration to save, the rationale, and anything worth knowing
     */
    public SynthesisResult synthesize(CouncilRequirement requirement,
                                      AdvisorEnvironment environment,
                                      UserConfigDocument existing,
                                      boolean shadowDefault) {
        UserConfigDocument base = existing == null ? UserConfigDocument.empty() : existing;
        List<String> rationale = new ArrayList<>();
        List<ConfigIssue> issues = new ArrayList<>();

        List<CandidateModel> pool = candidatePool(requirement, environment, base, rationale);
        if (pool.isEmpty()) {
            issues.add(nothingToSeat(requirement, environment));
            // The input document, not a stripped one. A failed re-run must not
            // take away the council the previous run produced.
            return new SynthesisResult(base, null, rationale, issues);
        }

        int requested = effectiveSize(requirement, rationale);
        List<CandidateModel> members = selectMembers(pool, requested, requirement);
        if (members.size() < requested) {
            issues.add(warning("profile:" + AdvisorIds.PROFILE, "councilSize",
                    "You asked for " + requirement.councilSize() + " council members and this "
                    + "machine can run " + members.size() + " distinct model"
                    + (members.size() == 1 ? "" : "s") + ".",
                    "Pull another Ollama model, or activate another provider, and run the "
                    + "advisor again."));
        }

        CandidateModel chair = selectChair(pool, members, requirement);
        CandidateModel validator = selectValidator(pool, chair);
        boolean independentValidator = !familyKey(validator).equals(familyKey(chair))
                                       && !validator.id().equals(chair.id());

        reportDiversity(members, issues);
        reportInferredFamilies(members, chair, validator, issues);
        reportSelfValidation(chair, validator, independentValidator, issues);

        Map<String, UserModel> defined = defineNewModels(members, chair, validator, requirement);
        Map<String, UserProtocol> protocols = deriveProtocols(requirement, rationale);
        String rigorousProtocolId = protocols.isEmpty()
                                    ? protocolIdFor(CouncilRequirement.Rigor.RIGOROUS)
                                    : AdvisorIds.FAST_RIGOROUS_PROTOCOL;
        Map<String, UserPolicy> policies =
                buildPolicies(members, chair, validator, independentValidator, rigorousProtocolId);
        Map<String, UserProfile> profiles = buildProfiles(requirement, base, shadowDefault, issues);

        explain(requirement, environment, members, chair, validator, independentValidator,
                defined, rationale);

        return new SynthesisResult(
                merge(base, defined.values(), policies, profiles, protocols),
                AdvisorIds.PROFILE, rationale, issues);
    }

    // ── Candidate pool ──────────────────────────────────────────────────

    /**
     * Every model this machine could seat, from all three sources.
     *
     * <p>Sources are consulted in order of preference for the same underlying
     * model: a built-in binding beats a user one, and both beat defining a new
     * model for a tag that is already bound. Selecting an existing binding over
     * creating a duplicate keeps the resulting configuration smaller and keeps
     * the ids stable across runs.
     */
    private List<CandidateModel> candidatePool(CouncilRequirement requirement,
                                               AdvisorEnvironment environment,
                                               UserConfigDocument base,
                                               List<String> rationale) {
        List<CandidateModel> pool = new ArrayList<>();
        Set<String> bindings = new LinkedHashSet<>();
        Set<String> takenIds = new LinkedHashSet<>();

        for (CandidateModel model : environment.catalogModels()) {
            takenIds.add(model.id());
            bindings.add(model.binding());
            if (acceptable(model, requirement, environment)) {
                pool.add(model);
            }
        }

        for (UserModel model : base.models()) {
            takenIds.add(model.id());
            if (AdvisorIds.owns(model.id())) {
                // The advisor's own previous output. It is re-derived from the
                // current environment rather than carried forward, so a model
                // uninstalled since the last run does not survive as a candidate.
                continue;
            }
            CandidateModel candidate = fromUserModel(model, environment);
            bindings.add(candidate.binding());
            if (acceptable(candidate, requirement, environment)) {
                pool.add(candidate);
            }
        }

        // Sorted so the ids generated below do not depend on the order Ollama
        // happened to list its models in.
        List<String> unbound = environment.installedOllamaTags().stream().sorted().toList();
        for (String tag : unbound) {
            CandidateModel candidate = fromInstalledTag(tag, takenIds);
            if (bindings.contains(candidate.binding())) {
                continue;
            }
            bindings.add(candidate.binding());
            takenIds.add(candidate.id());
            if (acceptable(candidate, requirement, environment)) {
                pool.add(candidate);
                rationale.add("'" + tag + "' is installed locally and no configured model binds it, "
                              + "so this configuration defines one as '" + candidate.id() + "'.");
            }
        }
        return pool;
    }

    /**
     * Whether a candidate may be seated at all.
     *
     * <p>The mock check is deliberately redundant with the availability check —
     * a mock client already classifies as {@link ClientAvailability#MOCK}. Two
     * checks for one rule, because a test-only model bound to a real provider
     * would slip past either one alone, and a council drawing on fabricated
     * output is the failure this codebase spends the most effort preventing.
     */
    private boolean acceptable(CandidateModel candidate, CouncilRequirement requirement,
                               AdvisorEnvironment environment) {
        if ("mock".equalsIgnoreCase(candidate.provider())) {
            return false;
        }
        if (candidate.availability() != ClientAvailability.LIVE) {
            return false;
        }
        if (candidate.local()) {
            return environment.isInstalled(candidate.providerModelId());
        }
        return !requirement.localOnly();
    }

    private CandidateModel fromUserModel(UserModel model, AdvisorEnvironment environment) {
        String provider = model.provider() == null ? "" : model.provider().toLowerCase(Locale.ROOT);
        return new CandidateModel(
                model.id(), provider, model.providerModelId(),
                ModelProfile.normaliseFamily(model.modelFamily()), false,
                enumOrDefault(model.role(), ModelRole.class, ModelRole.MEMBER),
                enumOrDefault(model.councilRole(), CouncilRole.class, CouncilRole.PROPOSER),
                model.contextWindowTokens() == null ? 0 : model.contextWindowTokens(),
                environment.availabilityOf(provider),
                CandidateModel.Source.USER);
    }

    private CandidateModel fromInstalledTag(String tag, Set<String> takenIds) {
        return new CandidateModel(
                AdvisorIds.modelId(tag, takenIds), AdvisorEnvironment.LOCAL_PROVIDER, tag,
                ModelFamilyHeuristic.infer(tag), true,
                ModelRole.MEMBER, CouncilRole.PROPOSER, 0,
                ClientAvailability.LIVE, CandidateModel.Source.NEW);
    }

    // ── Selection ───────────────────────────────────────────────────────

    private int effectiveSize(CouncilRequirement requirement, List<String> rationale) {
        if (requirement.latency() == CouncilRequirement.Latency.FAST
            && requirement.councilSize() > FAST_MAX_COUNCIL_SIZE) {
            rationale.add("You asked for a fast council, so the size is capped at "
                          + FAST_MAX_COUNCIL_SIZE + " rather than the "
                          + requirement.councilSize() + " requested: peer review grows with the "
                          + "square of the member count, so seats dominate how long a run takes.");
            return FAST_MAX_COUNCIL_SIZE;
        }
        return requirement.councilSize();
    }

    /**
     * Seat the council, maximising the number of distinct model families.
     *
     * <p>Two passes rather than one comparator. The first seats only candidates
     * that add a family nobody else brings; the second fills any remaining seats
     * with distinct underlying models. Members are deduplicated by
     * {@code provider:providerModelId} and not merely by id, because two ids
     * pointing at one set of weights draft twice, review each other, and skew the
     * scores while looking like two independent opinions.
     */
    private List<CandidateModel> selectMembers(List<CandidateModel> pool, int size,
                                               CouncilRequirement requirement) {
        List<CandidateModel> ranked = pool.stream().sorted(memberPreference(requirement)).toList();
        List<CandidateModel> chosen = new ArrayList<>();
        Set<String> bindings = new LinkedHashSet<>();
        Set<String> families = new LinkedHashSet<>();

        for (CandidateModel candidate : ranked) {
            if (chosen.size() >= size) {
                break;
            }
            if (bindings.contains(candidate.binding()) || families.contains(familyKey(candidate))) {
                continue;
            }
            chosen.add(candidate);
            bindings.add(candidate.binding());
            families.add(familyKey(candidate));
        }
        for (CandidateModel candidate : ranked) {
            if (chosen.size() >= size) {
                break;
            }
            if (bindings.contains(candidate.binding())) {
                continue;
            }
            chosen.add(candidate);
            bindings.add(candidate.binding());
        }
        return chosen;
    }

    /**
     * Ranking for member seats.
     *
     * <p>Local first unless the user said cost does not matter <em>and</em> cloud
     * models are welcome; then a model whose declared role is MEMBER; then the
     * larger context window, since the chair has to fit every draft into one
     * prompt; then id, so the result never depends on map iteration order.
     */
    private Comparator<CandidateModel> memberPreference(CouncilRequirement requirement) {
        boolean preferLocal = requirement.privacy() != CouncilRequirement.Privacy.CLOUD_OK
                              || requirement.cost() != CouncilRequirement.Cost.UNCONSTRAINED;
        Comparator<CandidateModel> comparator = Comparator.comparing(
                candidate -> preferLocal && candidate.local() ? 0 : 1);
        return comparator
                .thenComparing(candidate -> candidate.role() == ModelRole.MEMBER ? 0 : 1)
                .thenComparing(Comparator.comparingInt(CandidateModel::contextWindowTokens).reversed())
                .thenComparing(CandidateModel::id);
    }

    /**
     * Choose the chair, preferring a model configured for the job.
     *
     * <p>A model whose declared role is CHAIR carries a larger output budget,
     * because synthesis has to restate the council rather than one position. That
     * outranks finding a model the members are not already using: reusing a
     * member's weights for synthesis is what the shipped local profile does, and
     * it costs nothing extra at run time because the stages do not overlap.
     */
    private CandidateModel selectChair(List<CandidateModel> pool, List<CandidateModel> members,
                                       CouncilRequirement requirement) {
        Set<String> memberBindings = members.stream()
                                            .map(CandidateModel::binding)
                                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return pool.stream()
                   .min(Comparator
                                .comparing((CandidateModel candidate) ->
                                                   candidate.role() == ModelRole.CHAIR ? 0 : 1)
                                .thenComparing(candidate -> memberBindings.contains(candidate.binding()) ? 1 : 0)
                                .thenComparing(memberPreference(requirement)))
                   .orElseThrow(() -> new IllegalStateException("chair selected from an empty pool"));
    }

    /**
     * Choose the Fresh Eyes validator, preferring a different model family.
     *
     * <p>Family first, ahead of the declared VALIDATOR role, because a validator
     * that shares the chair's weights shares every one of its blind spots — a
     * validation pass it can never fail is worse than no validation pass, since
     * it produces a badge. Falling all the way back to the chair itself is
     * permitted, and reported: a one-model machine must still be able to run a
     * council.
     */
    private CandidateModel selectValidator(List<CandidateModel> pool, CandidateModel chair) {
        String chairFamily = familyKey(chair);
        return pool.stream()
                   .min(Comparator
                                .comparing((CandidateModel candidate) ->
                                                   familyKey(candidate).equals(chairFamily) ? 1 : 0)
                                .thenComparing(candidate -> candidate.role() == ModelRole.VALIDATOR ? 0 : 1)
                                .thenComparing(candidate -> candidate.binding().equals(chair.binding()) ? 1 : 0)
                                .thenComparing(Comparator.comparingInt(CandidateModel::contextWindowTokens).reversed())
                                .thenComparing(CandidateModel::id))
                   .orElse(chair);
    }

    // ── Reporting ───────────────────────────────────────────────────────

    private void reportDiversity(List<CandidateModel> members, List<ConfigIssue> issues) {
        long families = members.stream().map(this::familyKey).distinct().count();
        if (families > 1) {
            return;
        }
        String key = "policy:" + AdvisorIds.BALANCED_POLICY;
        if (members.size() <= 1) {
            issues.add(warning(key, "memberModelIds",
                    "This council has a single member, so peer review has nobody to disagree "
                    + "with and the anonymised review stage compares a draft only to itself.",
                    "Pull a second Ollama model from a different family, or activate another "
                    + "provider, then run the advisor again."));
            return;
        }
        issues.add(warning(key, "memberModelIds",
                "Every member of this council is from the '" + familyKey(members.getFirst())
                + "' family, so members share training data and failure modes and are likelier "
                + "to be wrong in the same direction.",
                "A second family is the single biggest improvement available here. For example: "
                + "ollama pull mistral:7b."));
    }

    private void reportInferredFamilies(List<CandidateModel> members, CandidateModel chair,
                                        CandidateModel validator, List<ConfigIssue> issues) {
        Set<CandidateModel> seated = new LinkedHashSet<>(members);
        seated.add(chair);
        seated.add(validator);
        seated.stream()
              .filter(CandidateModel::familyInferred)
              .forEach(candidate -> issues.add(warning(
                      "model:" + candidate.id(), "modelFamily",
                      "The model family '" + candidate.modelFamily() + "' was inferred from the name '"
                      + candidate.providerModelId() + "', not declared. Family is what decides whether "
                      + "this council can disagree with itself and whether validation is independent, "
                      + "so a wrong guess overstates both.",
                      "Check it before relying on the diversity and validation signals for this run.")));
    }

    private void reportSelfValidation(CandidateModel chair, CandidateModel validator,
                                      boolean independent, List<ConfigIssue> issues) {
        if (independent) {
            return;
        }
        boolean sameModel = validator.id().equals(chair.id());
        issues.add(warning("policy:" + AdvisorIds.BALANCED_POLICY, "validatorModelId",
                sameModel
                ? "The chair validates its own synthesis, because no other model is available. "
                  + "A validation pass by the author is not an independent check."
                : "The chair and validator are both from the '" + familyKey(chair) + "' family, so "
                  + "their errors are likely to be correlated.",
                "Results will report this as reduced validation independence rather than as a "
                + "clean validation. Adding a model from another family is what fixes it."));
    }

    // ── Entities ────────────────────────────────────────────────────────

    /**
     * Emit {@code models:} entries for the candidates nothing binds yet.
     *
     * <p>Only {@link CandidateModel.Source#NEW} candidates are defined. A
     * built-in is referenced by id and never copied: a copy would have to declare
     * a provider, and the provider allowlist a user configuration is held to is
     * narrower than the set the application can actually call, so copying would
     * work for some shipped models and fail validation for others.
     */
    private Map<String, UserModel> defineNewModels(List<CandidateModel> members,
                                                   CandidateModel chair,
                                                   CandidateModel validator,
                                                   CouncilRequirement requirement) {
        Map<String, ModelRole> roles = new LinkedHashMap<>();
        Map<String, CouncilRole> personas = new LinkedHashMap<>();
        Map<String, CandidateModel> byId = new LinkedHashMap<>();

        List<CouncilRole> memberPersonas = memberPersonas(members.size(), requirement);
        for (int index = 0; index < members.size(); index++) {
            CandidateModel member = members.get(index);
            byId.put(member.id(), member);
            roles.put(member.id(), ModelRole.MEMBER);
            personas.put(member.id(), memberPersonas.get(index));
        }
        // Chair and validator outrank a member seat for the same model: one
        // definition can carry only one role, and the more specific job wins.
        byId.put(validator.id(), validator);
        roles.put(validator.id(), ModelRole.VALIDATOR);
        personas.put(validator.id(), CouncilRole.CRITIC);
        byId.put(chair.id(), chair);
        roles.put(chair.id(), ModelRole.CHAIR);
        personas.put(chair.id(), CouncilRole.SYNTHESIZER);

        Map<String, UserModel> defined = new LinkedHashMap<>();
        byId.forEach((id, candidate) -> {
            if (candidate.source() != CandidateModel.Source.NEW) {
                return;
            }
            ModelRole role = roles.get(id);
            defined.put(id, new UserModel(
                    id, candidate.provider(), candidate.providerModelId(),
                    role == ModelRole.CHAIR ? CHAIR_OUTPUT_TOKENS : MEMBER_OUTPUT_TOKENS,
                    role == ModelRole.MEMBER ? MEMBER_TEMPERATURE : FOCUSED_TEMPERATURE,
                    LOCAL_TIMEOUT_SECONDS, null,
                    role.name(), personas.get(id).name(), candidate.modelFamily(),
                    null, null, null, null));
        });
        return defined;
    }

    /**
     * Debate personas for the member seats.
     *
     * <p>Alternating by default; weighted to critics when the user asked for it.
     * This only reaches models this configuration defines — a built-in keeps the
     * persona it ships with, because changing it would mean redefining it.
     */
    private List<CouncilRole> memberPersonas(int size, CouncilRequirement requirement) {
        List<CouncilRole> personas = new ArrayList<>();
        int critics = requirement.adversarialEmphasis() ? (size + 1) / 2 : size / 2;
        for (int index = 0; index < size; index++) {
            personas.add(index < critics ? CouncilRole.CRITIC : CouncilRole.PROPOSER);
        }
        return personas;
    }

    /**
     * Derive a tuned protocol, in the one case that calls for it.
     *
     * <p>The only tunable this ever writes is {@code DEBATE.max-rounds}. Nothing
     * here can reach {@code sycophancy-threshold} or {@code preserve-dissent},
     * so a synthesised protocol is structurally incapable of weakening an
     * anti-sycophancy guarantee — it is not a matter of choosing good defaults.
     */
    private Map<String, UserProtocol> deriveProtocols(CouncilRequirement requirement,
                                                      List<String> rationale) {
        if (requirement.latency() != CouncilRequirement.Latency.FAST
            || requirement.rigor() != CouncilRequirement.Rigor.RIGOROUS) {
            return Map.of();
        }
        rationale.add("A fast, rigorous council is a trade-off: debate is capped at "
                      + FAST_DEBATE_ROUNDS + " rounds instead of 3, so positions that would have "
                      + "converged on a third round are carried into synthesis as unresolved "
                      + "disagreement rather than settled. Nothing else about the protocol changes.");
        return Map.of(AdvisorIds.FAST_RIGOROUS_PROTOCOL, new UserProtocol(
                protocolIdFor(CouncilRequirement.Rigor.RIGOROUS),
                "Rigorous deliberation with debate capped at " + FAST_DEBATE_ROUNDS + " rounds.",
                Map.of(StageType.DEBATE.name(), Map.of("max-rounds", FAST_DEBATE_ROUNDS))));
    }

    /**
     * Build one policy per depth.
     *
     * <p>All three, whatever rigor was asked for. Rigor picks the profile's
     * <em>default</em> depth; removing the other two would mean a user who
     * described a quick council could never ask the same council to think
     * harder about one question.
     */
    private Map<String, UserPolicy> buildPolicies(List<CandidateModel> members,
                                                  CandidateModel chair,
                                                  CandidateModel validator,
                                                  boolean independentValidator,
                                                  String rigorousProtocolId) {
        List<String> allMembers = members.stream().map(CandidateModel::id).toList();
        // QUICK drafts once and synthesises: there is no review stage for a
        // second member to feed and no validation stage for a validator to run,
        // so seating them would cost tokens and change nothing.
        List<String> quickMembers = List.of(allMembers.getFirst());

        Map<String, UserPolicy> policies = new LinkedHashMap<>();
        policies.put(AdvisorIds.QUICK_POLICY, new UserPolicy(
                protocolIdFor(CouncilRequirement.Rigor.QUICK), quickMembers, chair.id(), null,
                1, 0, false, true, null));
        policies.put(AdvisorIds.BALANCED_POLICY, new UserPolicy(
                protocolIdFor(CouncilRequirement.Rigor.BALANCED), allMembers, chair.id(),
                validator.id(), draftQuorum(allMembers.size()), reviewQuorum(allMembers.size()),
                independentValidator, true, null));
        policies.put(AdvisorIds.RIGOROUS_POLICY, new UserPolicy(
                rigorousProtocolId, allMembers, chair.id(), validator.id(),
                draftQuorum(allMembers.size()), reviewQuorum(allMembers.size()),
                independentValidator, true, null));
        return policies;
    }

    private Map<String, UserProfile> buildProfiles(CouncilRequirement requirement,
                                                   UserConfigDocument base,
                                                   boolean shadowDefault,
                                                   List<ConfigIssue> issues) {
        Map<String, String> depthPolicies = new LinkedHashMap<>();
        depthPolicies.put("QUICK", AdvisorIds.QUICK_POLICY);
        depthPolicies.put("BALANCED", AdvisorIds.BALANCED_POLICY);
        depthPolicies.put("RIGOROUS", AdvisorIds.RIGOROUS_POLICY);

        Map<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put(AdvisorIds.PROFILE, new UserProfile(
                displayName(requirement), requirement.defaultDepth().name(), depthPolicies));

        if (!shadowDefault) {
            return profiles;
        }
        if (base.profiles().containsKey(AdvisorIds.DEFAULT_PROFILE)) {
            // Shadowing the built-in default is fine and is the intended use.
            // Overwriting the user's own default is the deletion this advisor
            // does not do.
            issues.add(warning("profile:" + AdvisorIds.DEFAULT_PROFILE, null,
                    "Your configuration already defines a 'default' profile, so it was left alone.",
                    "Select the '" + AdvisorIds.PROFILE + "' profile directly, or edit your own "
                    + "'default' profile to point at the advisor policies."));
            return profiles;
        }
        profiles.put(AdvisorIds.DEFAULT_PROFILE, new UserProfile(
                displayName(requirement), requirement.defaultDepth().name(), depthPolicies));
        return profiles;
    }

    /**
     * Merge the synthesised entities into the existing overlay, additively.
     *
     * <p>Everything the advisor does not own survives byte for byte, including
     * the {@code runtime:} section, which the advisor has no opinion about.
     */
    private UserConfigDocument merge(UserConfigDocument base,
                                     java.util.Collection<UserModel> models,
                                     Map<String, UserPolicy> policies,
                                     Map<String, UserProfile> profiles,
                                     Map<String, UserProtocol> protocols) {
        List<UserModel> mergedModels = new ArrayList<>(
                base.models().stream().filter(model -> !AdvisorIds.owns(model.id())).toList());
        mergedModels.addAll(models);

        return new UserConfigDocument(
                UserConfigDocument.SUPPORTED_VERSION,
                mergedModels,
                mergeKeyed(base.policies(), policies),
                mergeKeyed(base.profiles(), profiles),
                mergeKeyed(base.protocols(), protocols),
                base.runtime());
    }

    private <T> Map<String, T> mergeKeyed(Map<String, T> existing, Map<String, T> added) {
        Map<String, T> merged = new LinkedHashMap<>();
        existing.forEach((id, value) -> {
            if (!AdvisorIds.owns(id)) {
                merged.put(id, value);
            }
        });
        merged.putAll(added);
        return merged;
    }

    // ── Rationale ───────────────────────────────────────────────────────

    private void explain(CouncilRequirement requirement, AdvisorEnvironment environment,
                         List<CandidateModel> members, CandidateModel chair,
                         CandidateModel validator, boolean independentValidator,
                         Map<String, UserModel> defined, List<String> rationale) {

        rationale.add(switch (requirement.privacy()) {
            case LOCAL_ONLY -> "You asked to keep everything local, so only models installed in "
                               + "your Ollama runtime were considered; no cloud provider was "
                               + "consulted even where one is configured.";
            case PREFER_LOCAL -> "You preferred local models, so installed Ollama models were "
                                 + "ranked first and a cloud model would only have been seated to "
                                 + "fill a seat or add a family.";
            case CLOUD_OK -> "Any configured provider was acceptable, so models were ranked by "
                             + "family diversity and context window rather than by where they run.";
        });

        if (requirement.cost() == CouncilRequirement.Cost.FREE_ONLY) {
            rationale.add("You asked for no spend, which was read as local models only. A cloud "
                          + "model with no configured price is unpriced rather than free, so "
                          + "filtering on price would have seated one anyway.");
        }

        rationale.add("Seated " + members.size() + " member"
                      + (members.size() == 1 ? "" : "s") + " — "
                      + members.stream().map(CandidateModel::id).toList()
                      + " — covering " + members.stream().map(this::familyKey).distinct().count()
                      + " model famil"
                      + (members.stream().map(this::familyKey).distinct().count() == 1 ? "y" : "ies")
                      + ", because a council that shares a family tends to share its mistakes.");

        rationale.add("'" + chair.id() + "' chairs and synthesises"
                      + (chair.role() == ModelRole.CHAIR
                         ? ", which is the role it is configured for." : "."));

        rationale.add(independentValidator
                      ? "'" + validator.id() + "' validates the final answer and is from the '"
                        + familyKey(validator) + "' family, which the chair is not — so validation "
                        + "is an independent check rather than a second opinion from the same weights."
                      : "'" + validator.id() + "' validates, but shares the chair's family, so runs "
                        + "will report reduced validation independence and validation is not "
                        + "required to pass.");

        if (requirement.adversarialEmphasis()) {
            rationale.add("You asked for an adversarial council, so at least half the seats this "
                          + "configuration defines take the CRITIC persona. Built-in models keep "
                          + "the persona they ship with, because changing it would mean redefining "
                          + "them.");
        }

        rationale.add("Rigor " + requirement.rigor() + " sets the default depth to "
                      + requirement.defaultDepth() + ". All three depths are configured, so the "
                      + "same council can be asked to think harder about one question.");

        rationale.add("Quorum is " + draftQuorum(members.size()) + " of " + members.size()
                      + " drafts and " + reviewQuorum(members.size())
                      + " review(s) per draft, with partial results allowed.");

        if (!defined.isEmpty()) {
            rationale.add("This configuration defines " + defined.size() + " new model binding"
                          + (defined.size() == 1 ? "" : "s") + " for installed Ollama model"
                          + (defined.size() == 1 ? "" : "s") + " that nothing referenced: "
                          + defined.keySet() + ".");
        }

        if (!environment.liveCloudProviders().isEmpty() && requirement.localOnly()) {
            rationale.add("Configured but deliberately unused: "
                          + environment.liveCloudProviders() + ".");
        }

        rationale.add("Described as " + requirement.domains().stream().map(Enum::name).sorted().toList()
                      + ". This did not change model selection: this application records no "
                      + "per-model capability data, so choosing models by subject would be a guess "
                      + "presented as a reason. It appears in the profile name only.");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private ConfigIssue nothingToSeat(CouncilRequirement requirement,
                                      AdvisorEnvironment environment) {
        String key = "profile:" + AdvisorIds.PROFILE;
        if (requirement.localOnly()) {
            return error(key, null,
                    environment.hasLocalModels()
                    ? "No installed local model can be seated, so no council was created."
                    : "This machine has no local models installed, so a local-only council has "
                      + "nothing to seat. No configuration was changed.",
                    "Start Ollama if it is not running, then pull at least two models from "
                    + "different families — for example: ollama pull llama3.1:8b && "
                    + "ollama pull mistral:7b — and run the advisor again.");
        }
        return error(key, null,
                "No model on this machine can be called, so no council was created. Local models "
                + "are not installed and no cloud provider is active. No configuration was changed.",
                "Either pull a local model (ollama pull llama3.1:8b), or activate a provider by "
                + "setting its credential in your environment. "
                + "GET /api/council/catalog?include=providers names the variable each provider "
                + "needs; this application never reads credentials from configuration.");
    }

    /**
     * The draft quorum for a council of this size.
     *
     * @param size seated members
     * @return how many drafts must succeed, at least one and never more than the
     *         council can produce
     */
    private int draftQuorum(int size) {
        return Math.max(1, (int) Math.ceil(size * DRAFT_QUORUM_FRACTION));
    }

    /**
     * The review quorum for a council of this size.
     *
     * <p>One review wherever one is possible. This is a deliberate departure from
     * the plan's {@code size >= 3 ? 1 : 0}: with two members each draft can be
     * reviewed by the other, and a quorum of zero on a protocol that runs a
     * REVIEW stage means the stage's evidence is optional — which collapses
     * "reviewed and found sound" into "reviewed by nobody". A single member has
     * nobody to review it, since members never review their own draft.
     *
     * @param size seated members
     * @return reviews required per draft, never more than {@code size - 1}
     */
    private int reviewQuorum(int size) {
        return size >= 2 ? 1 : 0;
    }

    private String protocolIdFor(CouncilRequirement.Rigor rigor) {
        return rigor.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Group models that cannot be told apart for diversity purposes.
     *
     * <p>A blank family collapses with every other blank one rather than counting
     * as unique. That under-claims diversity, which is the safe direction: a
     * council reported as less able to disagree with itself than it is costs a
     * warning, while the reverse costs a trust signal that is wrong.
     */
    private String familyKey(CandidateModel candidate) {
        String family = ModelProfile.normaliseFamily(candidate.modelFamily());
        return family == null || family.isBlank() ? "" : family;
    }

    private String displayName(CouncilRequirement requirement) {
        String domains = requirement.domains().stream()
                                    .map(domain -> domain.name().toLowerCase(Locale.ROOT))
                                    .sorted()
                                    .reduce((left, right) -> left + ", " + right)
                                    .orElse("general");
        String name = "Advisor council (" + domains + ")";
        return name.length() <= ConfigLimits.MAX_DISPLAY_NAME_LENGTH
               ? name
               : name.substring(0, ConfigLimits.MAX_DISPLAY_NAME_LENGTH);
    }

    private <E extends Enum<E>> E enumOrDefault(String value, Class<E> type, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private ConfigIssue error(String key, String field, String message, String remediation) {
        return new ConfigIssue(ConfigIssue.Severity.ERROR, key, field, message, remediation);
    }

    private ConfigIssue warning(String key, String field, String message, String remediation) {
        return new ConfigIssue(ConfigIssue.Severity.WARNING, key, field, message, remediation);
    }
}
