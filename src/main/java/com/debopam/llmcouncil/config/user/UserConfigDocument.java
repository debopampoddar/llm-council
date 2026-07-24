package com.debopam.llmcouncil.config.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The user configuration overlay, exactly as written on disk.
 *
 * <p>This is a faithful mirror of the YAML file and performs no validation of
 * its own — {@link UserConfigValidator} owns every rule. Binding is
 * <b>strict</b>: an unrecognised field is an error rather than a silent no-op,
 * because a mistyped key that is quietly ignored looks identical to a setting
 * that took effect.
 *
 * <p>Note what is absent. There is no credentials section, no
 * {@code allowMockFallback}, no {@code testOnly}, and protocols carry no
 * {@code orderedStages}. Those are not omissions to fill in later: they are the
 * capability boundary expressed in the type system, so a user cannot express
 * them at all.
 *
 * @param version   schema version; only {@code 1} is understood today
 * @param models    model bindings, keyed by logical id
 * @param policies  policies, keyed by id
 * @param profiles  profiles, keyed by id
 * @param protocols derived protocols, keyed by id
 * @param runtime   runtime knobs, or null to leave defaults alone
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UserConfigDocument(
        Integer version,
        List<UserModel> models,
        Map<String, UserPolicy> policies,
        Map<String, UserProfile> profiles,
        Map<String, UserProtocol> protocols,
        UserRuntime runtime
) {

    /** The only schema version this application understands. */
    public static final int SUPPORTED_VERSION = 1;

    /** Normalises null collections so callers never have to null-check. */
    public UserConfigDocument {
        models = models == null ? List.of() : List.copyOf(models);
        policies = policies == null ? Map.of() : Map.copyOf(policies);
        profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
        protocols = protocols == null ? Map.of() : Map.copyOf(protocols);
    }

    /** @return an empty document, used when no overlay file exists */
    public static UserConfigDocument empty() {
        return new UserConfigDocument(SUPPORTED_VERSION, List.of(), Map.of(), Map.of(), Map.of(), null);
    }

    /** @return {@code true} when the document declares nothing at all */
    public boolean isEmpty() {
        return models.isEmpty() && policies.isEmpty() && profiles.isEmpty()
               && protocols.isEmpty() && runtime == null;
    }

    /**
     * A model binding: a logical id pointing at a provider model.
     *
     * <p>Users bind models, they do not integrate providers. {@code provider}
     * must name one the application already knows how to call.
     *
     * @param id                  logical id used in policies
     * @param provider            provider key from the supported set
     * @param providerModelId     provider-specific model name
     * @param defaultOutputTokens maximum output tokens per call
     * @param temperature         sampling temperature
     * @param timeoutSeconds      per-call timeout
     * @param contextWindowTokens total context window, or null to derive
     * @param role                MEMBER, CHAIR, or VALIDATOR
     * @param councilRole         PROPOSER, CRITIC, or SYNTHESIZER
     * @param modelFamily         architecture family tag for diversity checks
     * @param retryMaxAttempts    retry attempts for transient failures
     * @param retryBaseDelayMs    base backoff delay
     * @param costPer1kInputTokens  USD per 1,000 prompt tokens; zero or absent
     *                              means unpriced, which is reported as no cost
     *                              rather than as a cost of zero
     * @param costPer1kOutputTokens USD per 1,000 completion tokens
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UserModel(
            String id,
            String provider,
            String providerModelId,
            Integer defaultOutputTokens,
            Double temperature,
            Integer timeoutSeconds,
            Integer contextWindowTokens,
            String role,
            String councilRole,
            String modelFamily,
            Integer retryMaxAttempts,
            Long retryBaseDelayMs,
            Double costPer1kInputTokens,
            Double costPer1kOutputTokens
    ) {}

    /**
     * A policy: who sits on the council and what quorum it needs.
     *
     * @param protocolId                which protocol to run
     * @param memberModelIds            drafting members
     * @param chairModelId              synthesising chair
     * @param validatorModelId          Fresh Eyes validator, or null for none
     * @param minimumSuccessfulDrafts   draft quorum
     * @param minimumReviewsPerDraft    review quorum
     * @param validationRequired        whether validation must succeed
     * @param allowPartial              whether partial results are acceptable
     * @param acknowledgeSelfValidation silence the independence warning
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UserPolicy(
            String protocolId,
            List<String> memberModelIds,
            String chairModelId,
            String validatorModelId,
            Integer minimumSuccessfulDrafts,
            Integer minimumReviewsPerDraft,
            Boolean validationRequired,
            Boolean allowPartial,
            Boolean acknowledgeSelfValidation
    ) {}

    /**
     * A profile: the public-facing choice, mapping depth to policy.
     *
     * @param displayName   human-readable name
     * @param defaultDepth  depth applied when a request omits one
     * @param depthPolicies depth name to policy id
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UserProfile(
            String displayName,
            String defaultDepth,
            Map<String, String> depthPolicies
    ) {}

    /**
     * A derived protocol: a built-in protocol with tuned stage options.
     *
     * <p>There is deliberately no {@code orderedStages}. Stage order is the
     * deliberation design — anonymised review and adversarial roles are what the
     * council is for — so users tune protocols rather than compose them.
     * Supplying the key is a validation error, not a silent ignore.
     *
     * @param derivedFrom  the built-in protocol to clone; required
     * @param description  optional description
     * @param stageOptions stage name to option name to value
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UserProtocol(
            String derivedFrom,
            String description,
            Map<String, Map<String, Object>> stageOptions
    ) {}

    /**
     * Runtime knobs.
     *
     * @param maxConcurrentRuns   how many council runs may be active at once
     * @param chatRecentTurnCount how many prior turns feed a chat's context
     * @param artifactBasePath    where run artifacts are written
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UserRuntime(
            Integer maxConcurrentRuns,
            Integer chatRecentTurnCount,
            String artifactBasePath
    ) {}
}
