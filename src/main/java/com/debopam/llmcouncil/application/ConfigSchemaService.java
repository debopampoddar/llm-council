package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.ConfigSchemaResponse;
import com.debopam.llmcouncil.api.dto.ConfigSchemaResponse.EntitySchema;
import com.debopam.llmcouncil.api.dto.ConfigSchemaResponse.FieldSchema;
import com.debopam.llmcouncil.api.dto.ConfigSchemaResponse.FieldType;
import com.debopam.llmcouncil.api.dto.ConfigSchemaResponse.LockedRule;
import com.debopam.llmcouncil.api.dto.ConfigSchemaResponse.StageOptionSchema;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.config.user.ConfigLimits;
import com.debopam.llmcouncil.config.user.StageOptionSpec;
import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelRole;
import com.debopam.llmcouncil.orchestration.StageType;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Generates the configuration schema the UI builds its form from.
 *
 * <p>Every bound here is read from {@link ConfigLimits} or
 * {@link StageOptionSpec}, and every enumeration from the Java enum that defines
 * it. Nothing is restated. That is the whole point of the endpoint: the clamp
 * table exists once, and a form generated from it cannot offer a range the
 * validator will refuse.
 *
 * <p>Where a rule cannot be expressed as a static bound it is stated in the help
 * text instead of being approximated by a wrong number. {@code
 * minimumSuccessfulDrafts} is capped by the member count of its own policy, not
 * by a constant; publishing the constant as the maximum would let a form accept a
 * quorum the council can never meet.
 */
@Service
public class ConfigSchemaService {

    private final CouncilCatalogHolder catalogHolder;

    /**
     * @param catalogHolder holder for the configuration snapshots; the built-in
     *                      one supplies the protocols a user may derive from
     */
    public ConfigSchemaService(CouncilCatalogHolder catalogHolder) {
        this.catalogHolder = catalogHolder;
    }

    /**
     * Describe the overlay's shape.
     *
     * @return the schema, generated from the validator's bounds and the stage
     *         option table
     */
    public ConfigSchemaResponse schema() {
        List<String> derivableProtocols =
                catalogHolder.builtIn().protocols().keySet().stream().sorted().toList();

        return new ConfigSchemaResponse(
                UserConfigDocument.SUPPORTED_VERSION,
                List.of(modelEntity(), policyEntity(), profileEntity(),
                        protocolEntity(derivableProtocols), runtimeEntity(), retentionEntity()),
                stageOptions(),
                ConfigLimits.sortedProviders(),
                ConfigLimits.DEPTH_MODES,
                Arrays.stream(StageType.values()).map(Enum::name).toList(),
                lockedRules());
    }

    // ── Entities ────────────────────────────────────────────────────────

    private EntitySchema modelEntity() {
        List<FieldSchema> fields = List.of(
                id("Logical id used by policies. Must not collide with a built-in id unless "
                   + "you mean to replace that model."),
                enumeration("provider", true, ConfigLimits.sortedProviders(),
                            "Which integration calls this model. Adding a provider needs a code "
                            + "change, not configuration."),
                text("providerModelId", true, null,
                     "The model's name at the provider. For Ollama this is the tag you pulled, "
                     + "for example 'qwen2.5:14b'."),
                integer("defaultOutputTokens", ConfigLimits.MIN_OUTPUT_TOKENS,
                        ConfigLimits.MAX_OUTPUT_TOKENS, "Maximum tokens this model may produce per call."),
                fractional("temperature", ConfigLimits.MIN_TEMPERATURE, ConfigLimits.MAX_TEMPERATURE,
                           "Sampling temperature. Lower is more deterministic."),
                enumeration("reasoningEffort", false,
                            List.of("none", "low", "medium", "high", "xhigh", "max"),
                            "OpenAI GPT-5 reasoning effort. Leave blank to use the provider default."),
                integer("timeoutSeconds", ConfigLimits.MIN_TIMEOUT_SECONDS,
                        ConfigLimits.MAX_TIMEOUT_SECONDS,
                        "How long one call may take before it is treated as failed."),
                integer("contextWindowTokens", ConfigLimits.MIN_CONTEXT_TOKENS,
                        ConfigLimits.MAX_CONTEXT_TOKENS,
                        "Total context window, used for prompt budgeting. Leave unset to derive it."),
                enumeration("role", false, names(ModelRole.class),
                            "Structural seat on the council: drafting member, synthesising chair, "
                            + "or Fresh Eyes validator."),
                enumeration("councilRole", false, names(CouncilRole.class),
                            "Debate stance. A council of proposers agrees with itself."),
                text("modelFamily", false, null,
                     "Architecture tag such as 'llama' or 'claude'. Left blank, council diversity "
                     + "and validator independence cannot be assessed for this model."),
                integer("retryMaxAttempts", ConfigLimits.MIN_RETRY_ATTEMPTS,
                        ConfigLimits.MAX_RETRY_ATTEMPTS, "Retries for a transient failure."),
                integer("retryBaseDelayMs", (double) ConfigLimits.MIN_RETRY_DELAY_MS,
                        (double) ConfigLimits.MAX_RETRY_DELAY_MS, "Base backoff delay between retries."),
                fractional("costPer1kInputTokens", ConfigLimits.MIN_COST_PER_1K_TOKENS,
                           ConfigLimits.MAX_COST_PER_1K_TOKENS,
                           "USD per 1,000 prompt tokens. Zero means unpriced, which is reported as "
                           + "no cost rather than as a cost of nothing."),
                fractional("costPer1kOutputTokens", ConfigLimits.MIN_COST_PER_1K_TOKENS,
                           ConfigLimits.MAX_COST_PER_1K_TOKENS,
                           "USD per 1,000 completion tokens."));

        return new EntitySchema("model", false,
                                "A binding from a logical id to a model at a provider you already "
                                + "have configured.", fields);
    }

    private EntitySchema policyEntity() {
        return new EntitySchema("policy", true,
                "Who sits on the council and what quorum it needs.",
                List.of(
                        reference("protocolId", true,
                                  "Which protocol this policy runs. Built-in, or one you derived."),
                        idList("memberModelIds", ConfigLimits.MIN_MEMBERS, ConfigLimits.MAX_MEMBERS,
                               "The drafting members, in order. Larger councils multiply cost and "
                               + "overflow the chair's context window."),
                        reference("chairModelId", true, "The model that synthesises the final answer."),
                        reference("validatorModelId", false,
                                  "Fresh Eyes validator. A validator sharing the chair's model "
                                  + "validates its own synthesis, which is reported rather than refused."),
                        integer("minimumSuccessfulDrafts", (double) ConfigLimits.MIN_MEMBERS, null,
                                "Draft quorum. Cannot exceed this policy's own member count — a "
                                + "quorum larger than the council can never be met."),
                        integer("minimumReviewsPerDraft", 0.0, null,
                                "Review quorum. Cannot exceed the member count minus one, since a "
                                + "member does not review its own draft."),
                        flag("validationRequired",
                             "Whether the run fails when validation does not succeed."),
                        flag("allowPartial", "Whether a run may finish with some members missing."),
                        flag("acknowledgeSelfValidation",
                             "Silences the warning about a chair validating itself. The independence "
                             + "tier is still reported on every run.")));
    }

    private EntitySchema profileEntity() {
        return new EntitySchema("profile", true,
                "The public-facing choice: a name and a policy per depth.",
                List.of(
                        id("Profile id, as callers pass it in profileId."),
                        text("displayName", false, ConfigLimits.MAX_DISPLAY_NAME_LENGTH,
                             "Name shown in the picker."),
                        enumeration("defaultDepth", false, ConfigLimits.DEPTH_MODES,
                                    "Depth applied when a request omits one."),
                        new FieldSchema("depthPolicies", FieldType.DEPTH_POLICY_MAP, true,
                                        null, null, null, null, ConfigLimits.DEPTH_MODES, null,
                                        "Policy to run at each depth. Overriding a built-in profile "
                                        + "keeps the depths you do not mention.")));
    }

    private EntitySchema protocolEntity(List<String> derivableProtocols) {
        return new EntitySchema("protocol", true,
                "A tuned copy of a built-in protocol. Stage order is inherited, never supplied.",
                List.of(
                        id("Id for your tuned copy. It must not be a built-in protocol id."),
                        enumeration("derivedFrom", true, derivableProtocols,
                                    "The built-in protocol to clone."),
                        text("description", false, null, "What this tuning is for."),
                        new FieldSchema("stageOptions", FieldType.STAGE_OPTION_MAP, false,
                                        null, null, null, null, List.of(), null,
                                        "Per-stage option overrides. Permitted keys and their ranges "
                                        + "are listed under stageOptions on this response.")));
    }

    private EntitySchema runtimeEntity() {
        return new EntitySchema("runtime", false,
                "Machine-level knobs. Applied at startup.",
                List.of(
                        integer("maxConcurrentRuns", ConfigLimits.MIN_CONCURRENT_RUNS,
                                ConfigLimits.MAX_CONCURRENT_RUNS,
                                "How many council runs may be active at once. At 1, an unwanted run "
                                + "blocks every other run until it drains or is cancelled."),
                        integer("chatRecentTurnCount", ConfigLimits.MIN_RECENT_TURNS,
                                ConfigLimits.MAX_RECENT_TURNS,
                                "How many prior turns of a chat feed the next question's context."),
                        new FieldSchema("artifactBasePath", FieldType.ABSOLUTE_PATH, false,
                                        null, null, null, null, List.of(), null,
                                        "Where run artifacts are written. Must be absolute: a relative "
                                        + "path resolves against whatever directory the application "
                                        + "was started from."),
                        new FieldSchema("retention", FieldType.NESTED, false,
                                        null, null, null, null, List.of(), "retention",
                                        "Bounds on retained history.")));
    }

    private EntitySchema retentionEntity() {
        return new EntitySchema("retention", false,
                "How much history to keep. There is no value meaning unbounded — unbounded growth "
                + "is the defect these bounds replaced.",
                List.of(
                        integer("maxSessions", ConfigLimits.MIN_MAX_SESSIONS,
                                ConfigLimits.MAX_MAX_SESSIONS,
                                "Entries kept per store before the oldest are evicted. Entries still "
                                + "in use are never evicted, but still count toward this bound."),
                        integer("maxAgeDays", ConfigLimits.MIN_MAX_AGE_DAYS,
                                ConfigLimits.MAX_MAX_AGE_DAYS, "How long an untouched entry survives."),
                        integer("maxEventsPerSession", ConfigLimits.MIN_MAX_EVENTS_PER_SESSION,
                                ConfigLimits.MAX_MAX_EVENTS_PER_SESSION,
                                "Events kept for one session. Dropping events leaves a timeline with "
                                + "a hole, so this bound is generous.")));
    }

    // ── Stage options ───────────────────────────────────────────────────

    private List<StageOptionSchema> stageOptions() {
        return StageOptionSpec.all().stream()
                .map(spec -> new StageOptionSchema(
                        spec.stage().name(),
                        spec.key(),
                        fieldTypeOf(spec.type()),
                        spec.min(),
                        spec.max(),
                        spec.defaultValue(),
                        spec.allowedValues(),
                        spec.pattern(),
                        spec.integrityReducing(),
                        spec.description()))
                .toList();
    }

    private FieldType fieldTypeOf(StageOptionSpec.Type type) {
        return switch (type) {
            case INT -> FieldType.INT;
            case DOUBLE -> FieldType.DOUBLE;
            case BOOLEAN -> FieldType.BOOLEAN;
            case STRING -> FieldType.STRING;
            case ENUM -> FieldType.ENUM;
        };
    }

    // ── The hard boundary ───────────────────────────────────────────────

    /**
     * State what the overlay cannot express, and why.
     *
     * <p>A form that simply lacks a control for something looks like an
     * oversight. Naming the omission is what makes it read as a decision — which
     * matters most for credentials, where a user who cannot find the field will
     * otherwise go looking for somewhere to type one.
     */
    private List<LockedRule> lockedRules() {
        return List.of(
                new LockedRule("credentials",
                        "API keys, tokens, and passwords are never read from configuration. Set the "
                        + "provider's environment variable and restart. A file containing one is "
                        + "refused, and the value is never logged or returned."),
                new LockedRule("provider types",
                        "A provider needs a client implementation in the application. The list of "
                        + "providers on this response is complete."),
                new LockedRule("orderedStages",
                        "Stage order is the deliberation design, not a preference. Anonymised review "
                        + "and adversarial roles are what the council is for, so protocols are tuned "
                        + "rather than composed."),
                new LockedRule("testOnly",
                        "Forced false for anything a user defines. It is what keeps mock models, "
                        + "whose output is fabricated, out of real councils."),
                new LockedRule("allowMockFallback",
                        "Locked false. A configured profile never silently degrades to fabricated "
                        + "output; an unavailable provider fails with an actionable message instead."),
                new LockedRule("deleting built-in entities",
                        "Built-ins can be shadowed by an entity of the same id, or simply not "
                        + "selected, but never removed."),
                new LockedRule("these bounds",
                        "The ranges on this response are fixed in the application. A boundary whose "
                        + "limits can be raised from inside it is not one."));
    }

    // ── Field helpers ───────────────────────────────────────────────────

    private FieldSchema id(String help) {
        return new FieldSchema("id", FieldType.ID, true, null, null, null,
                               ConfigLimits.ID_PATTERN.pattern(), List.of(), null, help);
    }

    private FieldSchema reference(String name, boolean required, String help) {
        return new FieldSchema(name, FieldType.ID, required, null, null, null,
                               ConfigLimits.ID_PATTERN.pattern(), List.of(), null, help);
    }

    private FieldSchema idList(String name, int min, int max, String help) {
        return new FieldSchema(name, FieldType.ID_LIST, true, (double) min, (double) max,
                               null, ConfigLimits.ID_PATTERN.pattern(), List.of(), null, help);
    }

    private FieldSchema text(String name, boolean required, Integer maxLength, String help) {
        return new FieldSchema(name, FieldType.STRING, required, null, null, maxLength,
                               null, List.of(), null, help);
    }

    private FieldSchema enumeration(String name, boolean required, List<String> allowed, String help) {
        return new FieldSchema(name, FieldType.ENUM, required, null, null, null,
                               null, allowed, null, help);
    }

    private FieldSchema integer(String name, Double min, Double max, String help) {
        return new FieldSchema(name, FieldType.INT, false, min, max, null, null, List.of(), null, help);
    }

    private FieldSchema integer(String name, int min, int max, String help) {
        return integer(name, (double) min, (double) max, help);
    }

    private FieldSchema fractional(String name, double min, double max, String help) {
        return new FieldSchema(name, FieldType.DOUBLE, false, min, max, null, null,
                               List.of(), null, help);
    }

    private FieldSchema flag(String name, String help) {
        return new FieldSchema(name, FieldType.BOOLEAN, false, null, null, null, null,
                               List.of(), null, help);
    }

    private <E extends Enum<E>> List<String> names(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }
}
