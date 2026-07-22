package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.config.ConfigIssue;
import com.debopam.llmcouncil.config.ConfigOrigin;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.ValidationIndependence;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A projection of the active configuration snapshot.
 *
 * <p>Sections the caller did not request are {@code null} and are omitted from
 * the JSON entirely, so a client can tell "not requested" apart from "requested
 * and empty".
 *
 * <p>Everything here comes from a single {@code CouncilCatalog} read, so the
 * sections are always mutually consistent: a policy will never reference a model
 * that is missing from the {@code models} section of the same response.
 *
 * @param generation the catalog generation these sections were read from
 * @param builtAt    when that catalog was constructed
 * @param profiles   profile summaries, or null when not requested
 * @param policies   policy summaries, or null when not requested
 * @param models     model summaries, or null when not requested
 * @param protocols  protocol summaries, or null when not requested
 * @param providers  provider availability, or null when not requested
 * @param issues     configuration problems, or null when not requested
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogResponse(
        long generation,
        Instant builtAt,
        List<ProfileSummary> profiles,
        List<PolicySummary> policies,
        List<ModelSummary> models,
        List<ProtocolSummary> protocols,
        List<ProviderStatus> providers,
        List<ConfigIssue> issues
) {

    /**
     * A user-selectable profile.
     *
     * @param id               profile id used in requests
     * @param displayName      human-readable name
     * @param defaultDepth     depth applied when a request omits one
     * @param availableDepths  depths this profile maps to a policy
     * @param policyIdsByDepth the depth-to-policy mapping
     * @param testOnly         whether this profile is for tests only
     * @param origin           where this profile was defined
     */
    public record ProfileSummary(
            String id,
            String displayName,
            DepthMode defaultDepth,
            List<DepthMode> availableDepths,
            Map<DepthMode, String> policyIdsByDepth,
            boolean testOnly,
            ConfigOrigin origin
    ) {}

    /**
     * An execution policy.
     *
     * @param id                      policy id
     * @param protocolId              protocol this policy runs
     * @param memberModelIds          drafting members
     * @param chairModelId            synthesising chair
     * @param validatorModelId        Fresh Eyes validator, or null
     * @param minimumSuccessfulDrafts draft quorum
     * @param minimumReviewsPerDraft  review quorum
     * @param validationRequired      whether validation must succeed
     * @param allowPartial            whether partial results are acceptable
     * @param validationIndependence  how independent the validator is from the chair
     * @param origin                  where this policy was defined
     */
    public record PolicySummary(
            String id,
            String protocolId,
            List<String> memberModelIds,
            String chairModelId,
            String validatorModelId,
            int minimumSuccessfulDrafts,
            int minimumReviewsPerDraft,
            boolean validationRequired,
            boolean allowPartial,
            ValidationIndependence validationIndependence,
            ConfigOrigin origin
    ) {}

    /**
     * A configured model binding.
     *
     * <p>Contains no credential material of any kind — {@code clientKind}
     * reports whether a usable client was built, which is all a caller needs to
     * know about provider configuration.
     *
     * @param id                  logical model id used in policies
     * @param provider            provider key
     * @param providerModelId     provider-specific model name
     * @param defaultOutputTokens maximum output tokens per call
     * @param temperature         sampling temperature
     * @param timeoutSeconds      per-call timeout
     * @param role                structural role in the council
     * @param councilRole         debate persona
     * @param modelFamily         architecture family tag, may be null
     * @param clientKind          {@code LIVE}, {@code UNAVAILABLE}, or {@code MOCK}
     * @param origin              where this model was defined
     */
    public record ModelSummary(
            String id,
            String provider,
            String providerModelId,
            int defaultOutputTokens,
            double temperature,
            long timeoutSeconds,
            String role,
            String councilRole,
            String modelFamily,
            String clientKind,
            ConfigOrigin origin
    ) {}

    /**
     * A protocol definition.
     *
     * @param id            protocol id
     * @param description   what this protocol is for
     * @param orderedStages stages in execution order
     * @param stageOptions  per-stage tuning options
     * @param origin        where this protocol was defined
     */
    public record ProtocolSummary(
            String id,
            String description,
            List<String> orderedStages,
            Map<String, Map<String, Object>> stageOptions,
            ConfigOrigin origin
    ) {}

    /**
     * Whether a provider is usable, and what to do when it is not.
     *
     * <p>{@code reason} is a fixed explanatory string. It never contains the
     * credential value, a fragment of it, or its length.
     *
     * @param provider         provider key
     * @param active           whether models on this provider can run
     * @param reason           why the provider is inactive, or null when active
     * @param requiredEnvVar   the environment variable to set, or null when active
     * @param discoveredModels models found installed, for providers that can report them
     */
    public record ProviderStatus(
            String provider,
            boolean active,
            String reason,
            String requiredEnvVar,
            List<String> discoveredModels
    ) {}
}
