package com.debopam.llmcouncil.config.user;

import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.CouncilCatalog;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.orchestration.StageType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enforces what a user may and may not change.
 *
 * <p>The boundary is deliberate rather than incidental. Users decide <em>who
 * sits on the council</em> and <em>how carefully it deliberates</em>; they do
 * not decide how deliberation works. Anonymised peer review, adversarial roles,
 * and dissent-preserving synthesis are the product, so protocols are tuned
 * within bounds rather than composed freely, and stage order cannot be
 * expressed at all.
 *
 * <p>Validation is <b>fail-soft</b>. An invalid entity is dropped and reported;
 * the rest of the overlay still applies and the application still starts. This
 * is the opposite of built-in configuration, which fails fast, and the
 * difference is intentional: the application's own configuration is its
 * contract, while a user's file is something they edit by hand.
 *
 * <p>Rejection cascades. If dropping a bad model orphans a policy that
 * referenced it, that policy is dropped too, and so on to a fixed point — a
 * surviving entity that references a dropped one would fail at request time,
 * long after anyone was looking at the startup log.
 */
@Component
public class UserConfigValidator {

    // Must start and end alphanumeric: ids become map keys and URL path
    // segments, where a trailing hyphen is a nuisance rather than a choice.
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$");

    /** Providers a user may bind to. Adding a provider needs Java, not config. */
    private static final Set<String> ALLOWED_PROVIDERS = Set.of(
            "ollama", "openai", "anthropic", "gemini", "openai-compatible");

    private static final int MIN_OUTPUT_TOKENS = 64;
    private static final int MAX_OUTPUT_TOKENS = 32_000;
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 900;
    private static final int MIN_CONTEXT_TOKENS = 1_024;
    private static final int MAX_CONTEXT_TOKENS = 1_000_000;
    private static final int MAX_MEMBERS = 8;
    private static final int MIN_CONCURRENT_RUNS = 1;
    private static final int MAX_CONCURRENT_RUNS = 8;
    private static final int MIN_RECENT_TURNS = 1;
    private static final int MAX_RECENT_TURNS = 20;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long MIN_RETRY_DELAY_MS = 100L;
    private static final long MAX_RETRY_DELAY_MS = 30_000L;

    /**
     * Upper bound on a per-1,000-token price, in USD.
     *
     * <p>No provider charges anywhere near this. The bound exists to catch a
     * misplaced decimal point or a per-million figure pasted into a per-thousand
     * field, either of which would inflate every reported cost by three orders
     * of magnitude and make the spend signal actively misleading.
     */
    private static final double MAX_COST_PER_1K_TOKENS = 1000.0;

    /**
     * Validate an overlay against the built-in catalog.
     *
     * @param document the overlay as loaded
     * @param builtIn  the catalog of built-in configuration to resolve against
     * @return the sanitised document plus every issue found
     */
    public ValidationReport validate(UserConfigDocument document, CouncilCatalog builtIn) {
        List<ConfigIssue> issues = new ArrayList<>();

        Map<String, UserConfigDocument.UserModel> models =
                validateModels(document, builtIn, issues);
        Map<String, UserConfigDocument.UserProtocol> protocols =
                validateProtocols(document, builtIn, issues);
        Map<String, UserConfigDocument.UserPolicy> policies =
                validatePolicies(document, builtIn, models, protocols, issues);
        Map<String, UserConfigDocument.UserProfile> profiles =
                validateProfiles(document, builtIn, policies, issues);
        UserConfigDocument.UserRuntime runtime = validateRuntime(document.runtime(), issues);

        // Dropping an entity can orphan another that referenced it. Repeat until
        // nothing else falls out, so no surviving entity points at a hole.
        boolean changed = true;
        while (changed) {
            changed = removeOrphanedPolicies(policies, models, protocols, builtIn, issues);
            changed |= removeOrphanedProfiles(profiles, policies, builtIn, issues);
        }

        UserConfigDocument sanitised = new UserConfigDocument(
                UserConfigDocument.SUPPORTED_VERSION,
                List.copyOf(models.values()),
                policies, profiles, protocols, runtime);
        return new ValidationReport(sanitised, issues);
    }

    // ── Models ──────────────────────────────────────────────────────────

    private Map<String, UserConfigDocument.UserModel> validateModels(
            UserConfigDocument document, CouncilCatalog builtIn, List<ConfigIssue> issues) {

        Map<String, UserConfigDocument.UserModel> valid = new LinkedHashMap<>();
        for (UserConfigDocument.UserModel model : document.models()) {
            String key = CouncilCatalog.key("model", model.id() == null ? "<unnamed>" : model.id());
            List<ConfigIssue> modelIssues = new ArrayList<>();

            if (!hasValidId(model.id())) {
                modelIssues.add(error(key, "id",
                        "Model id must be lowercase letters, digits, and hyphens, 2 to 63 characters.",
                        "Rename the model, for example 'my-local-critic'."));
            } else if (valid.containsKey(model.id())) {
                modelIssues.add(error(key, "id", "Duplicate model id '" + model.id() + "'.",
                        "Give each model a unique id."));
            }

            String provider = model.provider() == null ? "" : model.provider().toLowerCase(Locale.ROOT);
            if (!ALLOWED_PROVIDERS.contains(provider)) {
                modelIssues.add(error(key, "provider",
                        "Provider '" + model.provider() + "' is not available to user configuration. "
                        + "Allowed: " + sorted(ALLOWED_PROVIDERS) + ".",
                        provider.equals("mock")
                        ? "Mock models never call a real provider, so a council using one produces "
                          + "fabricated output. They exist only for the built-in test profile."
                        : "Adding a new provider requires a ModelClient implementation in the application."));
            }
            if (isBlank(model.providerModelId())) {
                modelIssues.add(error(key, "providerModelId",
                        "providerModelId is required: it names the model at the provider.",
                        "For Ollama this is the tag you pulled, for example 'qwen2.5:14b'."));
            }

            checkRange(modelIssues, key, "defaultOutputTokens", model.defaultOutputTokens(),
                       MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS);
            checkRange(modelIssues, key, "timeoutSeconds", model.timeoutSeconds(),
                       MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
            checkRange(modelIssues, key, "contextWindowTokens", model.contextWindowTokens(),
                       MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS);
            checkRange(modelIssues, key, "retryMaxAttempts", model.retryMaxAttempts(), 0, MAX_RETRY_ATTEMPTS);
            checkRange(modelIssues, key, "costPer1kInputTokens", model.costPer1kInputTokens(),
                       0.0, MAX_COST_PER_1K_TOKENS);
            checkRange(modelIssues, key, "costPer1kOutputTokens", model.costPer1kOutputTokens(),
                       0.0, MAX_COST_PER_1K_TOKENS);
            if (model.temperature() != null && (model.temperature() < 0.0 || model.temperature() > 2.0)) {
                modelIssues.add(error(key, "temperature",
                        "temperature must be between 0.0 and 2.0, was " + model.temperature() + ".", null));
            }
            if (model.retryBaseDelayMs() != null
                && (model.retryBaseDelayMs() < MIN_RETRY_DELAY_MS || model.retryBaseDelayMs() > MAX_RETRY_DELAY_MS)) {
                modelIssues.add(error(key, "retryBaseDelayMs",
                        "retryBaseDelayMs must be between " + MIN_RETRY_DELAY_MS + " and "
                        + MAX_RETRY_DELAY_MS + ", was " + model.retryBaseDelayMs() + ".", null));
            }

            checkEnum(modelIssues, key, "role", model.role(), ModelRole.class);
            checkEnum(modelIssues, key, "councilRole", model.councilRole(), CouncilRole.class);

            if (modelIssues.isEmpty()) {
                // Only warn about models that survive. Advice about an entity
                // being dropped for another reason is noise.
                if (isBlank(model.modelFamily())) {
                    issues.add(warning(key, "modelFamily",
                            "Model '" + model.id() + "' has no modelFamily, so council diversity and "
                            + "validator independence cannot be assessed for it.",
                            "Set a short tag such as 'llama', 'qwen', or 'claude'."));
                }
                if (builtIn.modelRegistry().findModel(model.id()).isPresent()) {
                    issues.add(warning(key, "id",
                            "Model '" + model.id() + "' overrides a built-in model of the same id.",
                            "Use a different id if you meant to add a model rather than replace one."));
                }
                valid.put(model.id(), model);
            } else {
                issues.addAll(modelIssues);
            }
        }
        return valid;
    }

    // ── Protocols ───────────────────────────────────────────────────────

    private Map<String, UserConfigDocument.UserProtocol> validateProtocols(
            UserConfigDocument document, CouncilCatalog builtIn, List<ConfigIssue> issues) {

        Map<String, UserConfigDocument.UserProtocol> valid = new LinkedHashMap<>();
        document.protocols().forEach((id, protocol) -> {
            String key = CouncilCatalog.key("protocol", id);
            List<ConfigIssue> protocolIssues = new ArrayList<>();

            if (!hasValidId(id)) {
                protocolIssues.add(error(key, "id",
                        "Protocol id must be lowercase letters, digits, and hyphens.", null));
            }
            if (builtIn.protocols().containsKey(id)) {
                protocolIssues.add(error(key, "id",
                        "Protocol '" + id + "' would replace a built-in protocol.",
                        "Built-in protocols cannot be redefined. Choose a new id and set "
                        + "derivedFrom: " + id + " to tune a copy of it."));
            }
            if (isBlank(protocol.derivedFrom())) {
                protocolIssues.add(error(key, "derivedFrom",
                        "derivedFrom is required: a user protocol is always a tuned copy of a built-in one.",
                        "Set derivedFrom to one of " + sorted(builtIn.protocols().keySet()) + "."));
            } else if (!builtIn.protocols().containsKey(protocol.derivedFrom())) {
                protocolIssues.add(error(key, "derivedFrom",
                        "Unknown protocol '" + protocol.derivedFrom() + "'.",
                        "Available: " + sorted(builtIn.protocols().keySet()) + "."));
            }

            if (protocol.stageOptions() != null) {
                validateStageOptions(key, protocol.stageOptions(), protocolIssues, issues);
            }

            if (protocolIssues.isEmpty()) {
                valid.put(id, protocol);
            } else {
                issues.addAll(protocolIssues);
            }
        });
        return valid;
    }

    private void validateStageOptions(String key,
                                      Map<String, Map<String, Object>> stageOptions,
                                      List<ConfigIssue> errors,
                                      List<ConfigIssue> issues) {
        stageOptions.forEach((stageName, options) -> {
            StageType stage;
            try {
                stage = StageType.valueOf(stageName);
            } catch (IllegalArgumentException ex) {
                errors.add(error(key, "stageOptions." + stageName,
                        "Unknown stage '" + stageName + "'.",
                        "Valid stages: " + sorted(stageNames()) + "."));
                return;
            }
            if (options == null) {
                return;
            }
            options.forEach((option, value) -> {
                Optional<StageOptionSpec> spec = StageOptionSpec.find(stage, option);
                if (spec.isEmpty()) {
                    // Unknown options are silently ignored at run time, so
                    // accepting one would mean accepting a typo that appears to
                    // have worked.
                    errors.add(error(key, "stageOptions." + stageName + "." + option,
                            "Option '" + option + "' cannot be tuned on stage " + stageName + ".",
                            StageOptionSpec.keysFor(stage).isEmpty()
                            ? "This stage has no tunable options."
                            : "Tunable options for " + stageName + ": "
                              + String.join(", ", StageOptionSpec.keysFor(stage)) + "."));
                    return;
                }
                validateOptionValue(key, stageName, spec.get(), value, errors, issues);
            });
            validateRoundOrdering(key, stageName, options, errors);
        });
    }

    private void validateOptionValue(String key, String stageName, StageOptionSpec spec,
                                     Object value, List<ConfigIssue> errors, List<ConfigIssue> issues) {
        String field = "stageOptions." + stageName + "." + spec.key();
        if (value == null) {
            errors.add(error(key, field, "Option '" + spec.key() + "' has no value.", null));
            return;
        }
        String text = String.valueOf(value);

        switch (spec.type()) {
            case INT, DOUBLE -> {
                double number;
                try {
                    number = Double.parseDouble(text);
                } catch (NumberFormatException ex) {
                    errors.add(error(key, field,
                            "Option '" + spec.key() + "' must be a number.", spec.description()));
                    return;
                }
                if (spec.type() == StageOptionSpec.Type.INT && number != Math.floor(number)) {
                    errors.add(error(key, field,
                            "Option '" + spec.key() + "' must be a whole number.", spec.description()));
                    return;
                }
                if ((spec.min() != null && number < spec.min()) || (spec.max() != null && number > spec.max())) {
                    errors.add(error(key, field,
                            "Option '" + spec.key() + "' must be between " + format(spec.min())
                            + " and " + format(spec.max()) + ", was " + text + ".",
                            spec.description()));
                    return;
                }
                warnIfIntegrityReducing(key, field, spec, number, issues);
            }
            case BOOLEAN -> {
                if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                    errors.add(error(key, field,
                            "Option '" + spec.key() + "' must be true or false.", spec.description()));
                    return;
                }
                warnIfIntegrityReducing(key, field, spec, Boolean.parseBoolean(text) ? 1.0 : 0.0, issues);
            }
            case ENUM -> {
                if (!spec.allowedValues().contains(text)) {
                    errors.add(error(key, field,
                            "Option '" + spec.key() + "' must be one of "
                            + String.join(", ", spec.allowedValues()) + ", was '" + text + "'.",
                            spec.description()));
                }
            }
            case STRING -> {
                if (spec.pattern() != null && !Pattern.matches(spec.pattern(), text)) {
                    errors.add(error(key, field,
                            "Option '" + spec.key() + "' must match " + spec.pattern() + ".",
                            spec.description()));
                }
            }
        }
    }

    /**
     * Warn when a permitted value weakens an anti-sycophancy guarantee.
     *
     * <p>These settings are allowed — a user may have a reason — but a run using
     * them must not look identical to one that did not, so the warning is
     * recorded and the run is flagged.
     */
    private void warnIfIntegrityReducing(String key, String field, StageOptionSpec spec,
                                         double value, List<ConfigIssue> issues) {
        if (!spec.integrityReducing()) {
            return;
        }
        if (spec.key().equals("sycophancy-threshold") && value > 0.85) {
            issues.add(warning(key, field,
                    "sycophancy-threshold of " + format(value) + " suppresses most sycophancy warnings. "
                    + "Debate turns that merely agree with the previous speaker will pass unflagged.",
                    "Values above 0.85 hide the signal rather than improve it. Use 0.70 unless you "
                    + "are deliberately investigating false positives."));
        }
        if (spec.key().equals("preserve-dissent") && value == 0.0) {
            issues.add(warning(key, field,
                    "preserve-dissent is off, so the final answer will not report unresolved "
                    + "disagreement between council members.",
                    "The answer will read as more confident than the council actually was."));
        }
    }

    /** {@code max-rounds} below {@code min-rounds} is individually valid but jointly nonsense. */
    private void validateRoundOrdering(String key, String stageName,
                                       Map<String, Object> options, List<ConfigIssue> errors) {
        Object min = options.get("min-rounds");
        Object max = options.get("max-rounds");
        if (min == null || max == null) {
            return;
        }
        try {
            int minRounds = Integer.parseInt(String.valueOf(min));
            int maxRounds = Integer.parseInt(String.valueOf(max));
            if (maxRounds < minRounds) {
                errors.add(error(key, "stageOptions." + stageName + ".max-rounds",
                        "max-rounds (" + maxRounds + ") is below min-rounds (" + minRounds + ").",
                        "Raise max-rounds or lower min-rounds."));
            }
        } catch (NumberFormatException ignored) {
            // Already reported by the per-option range check.
        }
    }

    // ── Policies ────────────────────────────────────────────────────────

    private Map<String, UserConfigDocument.UserPolicy> validatePolicies(
            UserConfigDocument document, CouncilCatalog builtIn,
            Map<String, UserConfigDocument.UserModel> userModels,
            Map<String, UserConfigDocument.UserProtocol> userProtocols,
            List<ConfigIssue> issues) {

        Map<String, UserConfigDocument.UserPolicy> valid = new LinkedHashMap<>();
        document.policies().forEach((id, policy) -> {
            String key = CouncilCatalog.key("policy", id);
            List<ConfigIssue> policyIssues = new ArrayList<>();

            if (!hasValidId(id)) {
                policyIssues.add(error(key, "id",
                        "Policy id must be lowercase letters, digits, and hyphens.", null));
            }
            if (isBlank(policy.protocolId())) {
                policyIssues.add(error(key, "protocolId", "protocolId is required.",
                        "Available: " + sorted(knownProtocols(builtIn, userProtocols)) + "."));
            } else if (!knownProtocols(builtIn, userProtocols).contains(policy.protocolId())) {
                policyIssues.add(error(key, "protocolId",
                        "Unknown protocol '" + policy.protocolId() + "'.",
                        "Available: " + sorted(knownProtocols(builtIn, userProtocols)) + "."));
            }

            List<String> members = policy.memberModelIds() == null ? List.of() : policy.memberModelIds();
            if (members.isEmpty()) {
                policyIssues.add(error(key, "memberModelIds",
                        "A policy needs at least one member model.", null));
            }
            if (members.size() > MAX_MEMBERS) {
                policyIssues.add(error(key, "memberModelIds",
                        "A policy may have at most " + MAX_MEMBERS + " members, found " + members.size() + ".",
                        "Larger councils multiply cost and overflow the chair's context window."));
            }
            if (new LinkedHashSet<>(members).size() != members.size()) {
                policyIssues.add(error(key, "memberModelIds",
                        "memberModelIds contains duplicates.",
                        "A model listed twice drafts twice and skews scoring."));
            }
            for (int i = 0; i < members.size(); i++) {
                checkModelReference(key, "memberModelIds[" + i + "]", members.get(i),
                                    builtIn, userModels, policyIssues);
            }
            checkModelReference(key, "chairModelId", policy.chairModelId(), builtIn, userModels, policyIssues);

            boolean validationRequired = Boolean.TRUE.equals(policy.validationRequired());
            if (!isBlank(policy.validatorModelId())) {
                checkModelReference(key, "validatorModelId", policy.validatorModelId(),
                                    builtIn, userModels, policyIssues);
            } else if (validationRequired) {
                policyIssues.add(error(key, "validatorModelId",
                        "validationRequired is true but no validatorModelId is set.",
                        "Name a validator, or set validationRequired: false."));
            }

            Integer minDrafts = policy.minimumSuccessfulDrafts();
            if (minDrafts != null && (minDrafts < 1 || minDrafts > Math.max(1, members.size()))) {
                policyIssues.add(error(key, "minimumSuccessfulDrafts",
                        "minimumSuccessfulDrafts must be between 1 and the member count ("
                        + members.size() + "), was " + minDrafts + ".",
                        "A quorum larger than the council can never be met."));
            }
            Integer minReviews = policy.minimumReviewsPerDraft();
            if (minReviews != null && (minReviews < 0 || minReviews > Math.max(0, members.size() - 1))) {
                policyIssues.add(error(key, "minimumReviewsPerDraft",
                        "minimumReviewsPerDraft must be between 0 and " + Math.max(0, members.size() - 1)
                        + " (members cannot review their own draft), was " + minReviews + ".", null));
            }

            if (policyIssues.isEmpty()) {
                warnIfValidatorNotIndependent(key, policy, builtIn, userModels, issues);
                valid.put(id, policy);
            } else {
                issues.addAll(policyIssues);
            }
        });
        return valid;
    }

    /**
     * Warn when a user policy's validator shares the chair's model.
     *
     * <p>Mirrors the check applied to built-in policies. A council whose chair
     * validates its own synthesis is permitted — a single-model machine has no
     * alternative — but it must not report validated output as though an
     * independent check occurred.
     */
    private void warnIfValidatorNotIndependent(String key, UserConfigDocument.UserPolicy policy,
                                               CouncilCatalog builtIn,
                                               Map<String, UserConfigDocument.UserModel> userModels,
                                               List<ConfigIssue> issues) {
        if (isBlank(policy.validatorModelId()) || Boolean.TRUE.equals(policy.acknowledgeSelfValidation())) {
            return;
        }
        if (policy.validatorModelId().equals(policy.chairModelId())) {
            issues.add(warning(key, "validatorModelId",
                    "Chair and validator are the same model, so the chair validates its own synthesis "
                    + "and shares all of its own blind spots.",
                    "Use a validator from a different model family, or set "
                    + "acknowledgeSelfValidation: true to accept this deliberately."));
            return;
        }
        String chairFamily = familyOf(policy.chairModelId(), builtIn, userModels);
        String validatorFamily = familyOf(policy.validatorModelId(), builtIn, userModels);
        if (chairFamily != null && chairFamily.equals(validatorFamily)) {
            issues.add(warning(key, "validatorModelId",
                    "Chair and validator are both from the '" + chairFamily + "' family, so their "
                    + "errors are likely to be correlated.",
                    "Prefer a validator from a different model family."));
        }
    }

    private String familyOf(String modelId, CouncilCatalog builtIn,
                            Map<String, UserConfigDocument.UserModel> userModels) {
        UserConfigDocument.UserModel userModel = userModels.get(modelId);
        if (userModel != null) {
            return userModel.modelFamily();
        }
        return builtIn.modelRegistry().findModel(modelId)
                      .map(model -> model.modelFamily())
                      .orElse(null);
    }

    private void checkModelReference(String key, String field, String modelId, CouncilCatalog builtIn,
                                     Map<String, UserConfigDocument.UserModel> userModels,
                                     List<ConfigIssue> issues) {
        if (isBlank(modelId)) {
            issues.add(error(key, field, field + " is required.", null));
            return;
        }
        if (userModels.containsKey(modelId)) {
            return;
        }
        if (builtIn.modelRegistry().findModel(modelId).isPresent()) {
            // A real council must not draw on models that exist only for tests.
            if (isTestOnlyModel(modelId, builtIn)) {
                issues.add(error(key, field,
                        "Model '" + modelId + "' exists only for the built-in test profile.",
                        "Mock models never call a real provider, so a council using one produces "
                        + "fabricated output."));
            }
            return;
        }
        issues.add(error(key, field, "Unknown model '" + modelId + "'.",
                         "Define it under models: in this file, or use a built-in model id."));
    }

    private boolean isTestOnlyModel(String modelId, CouncilCatalog builtIn) {
        return builtIn.modelRegistry().findModel(modelId)
                      .map(model -> "mock".equalsIgnoreCase(model.provider()))
                      .orElse(false);
    }

    // ── Profiles ────────────────────────────────────────────────────────

    private Map<String, UserConfigDocument.UserProfile> validateProfiles(
            UserConfigDocument document, CouncilCatalog builtIn,
            Map<String, UserConfigDocument.UserPolicy> userPolicies,
            List<ConfigIssue> issues) {

        Map<String, UserConfigDocument.UserProfile> valid = new LinkedHashMap<>();
        document.profiles().forEach((id, profile) -> {
            String key = CouncilCatalog.key("profile", id);
            List<ConfigIssue> profileIssues = new ArrayList<>();

            if (!hasValidId(id)) {
                profileIssues.add(error(key, "id",
                        "Profile id must be lowercase letters, digits, and hyphens.", null));
            }
            if (builtIn.profiles().containsKey(id) && builtIn.profiles().get(id).testOnly()) {
                profileIssues.add(error(key, "id",
                        "Profile '" + id + "' is a built-in test profile and cannot be overridden.",
                        "Choose a different id."));
            }
            if (!isBlank(profile.displayName()) && profile.displayName().length() > 80) {
                profileIssues.add(error(key, "displayName",
                        "displayName must be 80 characters or fewer.", null));
            }

            DepthMode defaultDepth = null;
            if (!isBlank(profile.defaultDepth())) {
                try {
                    defaultDepth = DepthMode.valueOf(profile.defaultDepth().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    profileIssues.add(error(key, "defaultDepth",
                            "Unknown depth '" + profile.defaultDepth() + "'.",
                            "Valid depths: QUICK, BALANCED, RIGOROUS."));
                }
            }

            Map<String, String> depthPolicies =
                    profile.depthPolicies() == null ? Map.of() : profile.depthPolicies();
            Set<DepthMode> declared = new LinkedHashSet<>();
            depthPolicies.forEach((depth, policyId) -> {
                DepthMode mode;
                try {
                    mode = DepthMode.valueOf(depth.toUpperCase(Locale.ROOT));
                    declared.add(mode);
                } catch (IllegalArgumentException ex) {
                    profileIssues.add(error(key, "depthPolicies." + depth,
                            "Unknown depth '" + depth + "'.",
                            "Valid depths: QUICK, BALANCED, RIGOROUS."));
                    return;
                }
                if (!userPolicies.containsKey(policyId) && !builtIn.policies().containsKey(policyId)) {
                    profileIssues.add(error(key, "depthPolicies." + depth,
                            "Unknown policy '" + policyId + "'.",
                            "Define it under policies: in this file, or use a built-in policy id."));
                }
            });

            boolean overridesBuiltIn = builtIn.profiles().containsKey(id);
            if (depthPolicies.isEmpty() && !overridesBuiltIn) {
                profileIssues.add(error(key, "depthPolicies",
                        "A new profile must map at least one depth to a policy.", null));
            }
            if (defaultDepth != null && !declared.contains(defaultDepth) && !overridesBuiltIn) {
                profileIssues.add(error(key, "defaultDepth",
                        "defaultDepth is " + defaultDepth + " but no policy is mapped for that depth.",
                        "Add a " + defaultDepth + " entry under depthPolicies."));
            }

            if (profileIssues.isEmpty()) {
                if (overridesBuiltIn) {
                    issues.add(warning(key, null,
                            "Profile '" + id + "' overrides a built-in profile; unspecified depths "
                            + "keep their built-in policies.", null));
                }
                valid.put(id, profile);
            } else {
                issues.addAll(profileIssues);
            }
        });
        return valid;
    }

    // ── Runtime ─────────────────────────────────────────────────────────

    private UserConfigDocument.UserRuntime validateRuntime(UserConfigDocument.UserRuntime runtime,
                                                           List<ConfigIssue> issues) {
        if (runtime == null) {
            return null;
        }
        List<ConfigIssue> runtimeIssues = new ArrayList<>();
        checkRange(runtimeIssues, "runtime", "maxConcurrentRuns", runtime.maxConcurrentRuns(),
                   MIN_CONCURRENT_RUNS, MAX_CONCURRENT_RUNS);
        checkRange(runtimeIssues, "runtime", "chatRecentTurnCount", runtime.chatRecentTurnCount(),
                   MIN_RECENT_TURNS, MAX_RECENT_TURNS);

        if (runtime.artifactBasePath() != null && !runtime.artifactBasePath().isBlank()) {
            java.nio.file.Path path;
            try {
                path = java.nio.file.Path.of(runtime.artifactBasePath());
            } catch (java.nio.file.InvalidPathException ex) {
                runtimeIssues.add(error("runtime", "artifactBasePath",
                        "artifactBasePath is not a valid path.", null));
                path = null;
            }
            if (path != null && !path.isAbsolute()) {
                runtimeIssues.add(error("runtime", "artifactBasePath",
                        "artifactBasePath must be absolute, was '" + runtime.artifactBasePath() + "'.",
                        "A relative path resolves against the working directory, which changes "
                        + "depending on how the application was started."));
            }
        }

        if (!runtimeIssues.isEmpty()) {
            issues.addAll(runtimeIssues);
            return null;
        }
        return runtime;
    }

    // ── Cascade ─────────────────────────────────────────────────────────

    private boolean removeOrphanedPolicies(Map<String, UserConfigDocument.UserPolicy> policies,
                                           Map<String, UserConfigDocument.UserModel> models,
                                           Map<String, UserConfigDocument.UserProtocol> protocols,
                                           CouncilCatalog builtIn,
                                           List<ConfigIssue> issues) {
        List<String> orphaned = policies.entrySet().stream()
                .filter(entry -> referencesMissing(entry.getValue(), models, protocols, builtIn))
                .map(Map.Entry::getKey)
                .toList();
        orphaned.forEach(id -> {
            policies.remove(id);
            issues.add(error(CouncilCatalog.key("policy", id), null,
                    "Policy '" + id + "' was removed because something it referenced was rejected.",
                    "Fix the earlier error and this policy will load."));
        });
        return !orphaned.isEmpty();
    }

    private boolean referencesMissing(UserConfigDocument.UserPolicy policy,
                                      Map<String, UserConfigDocument.UserModel> models,
                                      Map<String, UserConfigDocument.UserProtocol> protocols,
                                      CouncilCatalog builtIn) {
        if (policy.protocolId() != null
            && !protocols.containsKey(policy.protocolId())
            && !builtIn.protocols().containsKey(policy.protocolId())) {
            return true;
        }
        List<String> referenced = new ArrayList<>(
                policy.memberModelIds() == null ? List.of() : policy.memberModelIds());
        referenced.add(policy.chairModelId());
        if (!isBlank(policy.validatorModelId())) {
            referenced.add(policy.validatorModelId());
        }
        return referenced.stream().anyMatch(modelId ->
                modelId != null
                && !models.containsKey(modelId)
                && builtIn.modelRegistry().findModel(modelId).isEmpty());
    }

    private boolean removeOrphanedProfiles(Map<String, UserConfigDocument.UserProfile> profiles,
                                           Map<String, UserConfigDocument.UserPolicy> policies,
                                           CouncilCatalog builtIn,
                                           List<ConfigIssue> issues) {
        List<String> orphaned = profiles.entrySet().stream()
                .filter(entry -> {
                    Map<String, String> depthPolicies = entry.getValue().depthPolicies();
                    return depthPolicies != null && depthPolicies.values().stream().anyMatch(policyId ->
                            !policies.containsKey(policyId) && !builtIn.policies().containsKey(policyId));
                })
                .map(Map.Entry::getKey)
                .toList();
        orphaned.forEach(id -> {
            profiles.remove(id);
            issues.add(error(CouncilCatalog.key("profile", id), null,
                    "Profile '" + id + "' was removed because a policy it maps to was rejected.",
                    "Fix the earlier error and this profile will load."));
        });
        return !orphaned.isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Set<String> knownProtocols(CouncilCatalog builtIn,
                                       Map<String, UserConfigDocument.UserProtocol> userProtocols) {
        Set<String> known = new LinkedHashSet<>(builtIn.protocols().keySet());
        known.addAll(userProtocols.keySet());
        return known;
    }

    private void checkRange(List<ConfigIssue> issues, String key, String field,
                            Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            issues.add(error(key, field,
                    field + " must be between " + min + " and " + max + ", was " + value + ".", null));
        }
    }

    /**
     * Range check for a fractional field.
     *
     * <p>Separate from the {@code Integer} overload rather than widening it,
     * because the message has to print the bounds the user actually wrote:
     * "between 0 and 1000" reads wrong on a field whose useful values are all
     * below one.
     */
    private void checkRange(List<ConfigIssue> issues, String key, String field,
                            Double value, double min, double max) {
        if (value != null && (value < min || value > max)) {
            issues.add(error(key, field,
                    field + " must be between " + min + " and " + max + ", was " + value + ".", null));
        }
    }

    private <E extends Enum<E>> void checkEnum(List<ConfigIssue> issues, String key, String field,
                                               String value, Class<E> type) {
        if (isBlank(value)) {
            return;
        }
        try {
            Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            issues.add(error(key, field,
                    "Unknown " + field + " '" + value + "'.",
                    "Valid values: " + String.join(", ",
                            java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()) + "."));
        }
    }

    private List<String> stageNames() {
        return java.util.Arrays.stream(StageType.values()).map(Enum::name).sorted().toList();
    }

    private String sorted(java.util.Collection<String> values) {
        return String.join(", ", values.stream().sorted().toList());
    }

    private String format(Double value) {
        if (value == null) {
            return "unbounded";
        }
        return value == Math.floor(value) ? String.valueOf(value.intValue()) : String.valueOf(value);
    }

    private boolean hasValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ConfigIssue error(String key, String field, String message, String remediation) {
        return new ConfigIssue(ConfigIssue.Severity.ERROR, key, field, message, remediation);
    }

    private ConfigIssue warning(String key, String field, String message, String remediation) {
        return new ConfigIssue(ConfigIssue.Severity.WARNING, key, field, message, remediation);
    }

    /**
     * The outcome of validating an overlay.
     *
     * @param sanitised the document with every rejected entity removed
     * @param issues    everything found, errors and warnings alike
     */
    public record ValidationReport(UserConfigDocument sanitised, List<ConfigIssue> issues) {

        /** @return issues that caused an entity to be dropped */
        public List<ConfigIssue> errors() {
            return issues.stream().filter(issue -> issue.severity() == ConfigIssue.Severity.ERROR).toList();
        }

        /** @return issues worth knowing that did not drop anything */
        public List<ConfigIssue> warnings() {
            return issues.stream().filter(issue -> issue.severity() == ConfigIssue.Severity.WARNING).toList();
        }
    }
}
