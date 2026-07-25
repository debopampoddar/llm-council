package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilPolicy;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.MockModelClient;
import com.debopam.llmcouncil.model.ModelClient;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRegistry;
import com.debopam.llmcouncil.model.ModelRole;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builders for the configuration types tests need most.
 *
 * <p>These exist for one reason: {@link ModelProfile} has twelve components and
 * {@link CouncilPolicy} has nine, and almost every test cares about two of them.
 * Written out positionally, a test that is about model <em>families</em> spends
 * four lines saying nothing about families, and adding a component to the record
 * means editing a dozen files that never mentioned it.
 *
 * <p>Defaults are the boring case — a local Ollama member with no price and no
 * declared context window — so a test names only what it is actually about. That
 * is also what makes the tests readable as statements: {@code model("chair")
 * .role(CHAIR).family("llama")} says what it is testing, where thirteen
 * positional arguments say only that the constructor was satisfied.
 *
 * <p>Companion to {@link TestCatalogs}, which assembles these into a
 * {@link CouncilCatalog}. This one builds the pieces; that one builds the whole.
 */
public final class TestModels {

    /** Output tokens for a model whose token budget is not what is under test. */
    public static final int DEFAULT_OUTPUT_TOKENS = 1200;

    /** Timeout for a model whose timeout is not what is under test. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private TestModels() {
    }

    /**
     * Start building a model.
     *
     * @param id the logical model id; also seeds {@code providerModelId}
     * @return a builder defaulting to a local Ollama member
     */
    public static ModelBuilder model(String id) {
        return new ModelBuilder(id);
    }

    /**
     * Start building a policy.
     *
     * @param id the policy id
     * @return a builder with a single member, no validator, and quorum of one
     */
    public static PolicyBuilder policy(String id) {
        return new PolicyBuilder(id);
    }

    /**
     * Start building a profile.
     *
     * @param id the profile id
     * @return a builder defaulting to BALANCED with no depths mapped yet
     */
    public static ProfileBuilder profile(String id) {
        return new ProfileBuilder(id);
    }

    /**
     * Build a registry over the given models, each backed by a mock client.
     *
     * <p>Mock clients because a registry assembled in a test is nearly always
     * about resolution, quorum, or diversity rather than about what a provider
     * would return. A test that cares what the client does supplies its own.
     *
     * @param models the models to register
     * @return a registry with a mock client per model
     */
    public static ModelRegistry registry(ModelProfile... models) {
        Map<String, ModelProfile> profiles = new LinkedHashMap<>();
        Map<String, ModelClient> clients = new LinkedHashMap<>();
        Arrays.stream(models).forEach(model -> {
            profiles.put(model.id(), model);
            clients.put(model.id(), new MockModelClient(model.id()));
        });
        return new ModelRegistry(profiles, clients);
    }

    /**
     * Build a registry with explicit clients.
     *
     * @param models  the models to register
     * @param clients model id to the client backing it
     * @return a registry pairing the two
     */
    public static ModelRegistry registry(List<ModelProfile> models, Map<String, ModelClient> clients) {
        Map<String, ModelProfile> profiles = new LinkedHashMap<>();
        models.forEach(model -> profiles.put(model.id(), model));
        return new ModelRegistry(profiles, clients);
    }

    /** Fluent builder for {@link ModelProfile}. */
    public static final class ModelBuilder {

        private final String id;
        private String provider = "ollama";
        private String providerModelId;
        private int outputTokens = DEFAULT_OUTPUT_TOKENS;
        private double temperature = 0.3;
        private Duration timeout = DEFAULT_TIMEOUT;
        private ModelRole role = ModelRole.MEMBER;
        private CouncilRole councilRole = CouncilRole.PROPOSER;
        private String family;
        private int contextWindowTokens;
        private double inputCost;
        private double outputCost;

        private ModelBuilder(String id) {
            this.id = id;
            this.providerModelId = id + "-model";
        }

        /** @param provider provider key @return this builder */
        public ModelBuilder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /** @param providerModelId the model's name at the provider @return this builder */
        public ModelBuilder providerModelId(String providerModelId) {
            this.providerModelId = providerModelId;
            return this;
        }

        /** @param outputTokens maximum output tokens @return this builder */
        public ModelBuilder outputTokens(int outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        /** @param temperature sampling temperature @return this builder */
        public ModelBuilder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /** @param timeout per-call timeout @return this builder */
        public ModelBuilder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** @param role structural role @return this builder */
        public ModelBuilder role(ModelRole role) {
            this.role = role;
            return this;
        }

        /** @param councilRole debate persona @return this builder */
        public ModelBuilder councilRole(CouncilRole councilRole) {
            this.councilRole = councilRole;
            return this;
        }

        /** @param family architecture family tag @return this builder */
        public ModelBuilder family(String family) {
            this.family = family;
            return this;
        }

        /** @param contextWindowTokens total context window @return this builder */
        public ModelBuilder contextWindow(int contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
            return this;
        }

        /**
         * Give the model a price.
         *
         * <p>Named rather than defaulted because zero means <em>unpriced</em>, not
         * free, and a test about cost reporting has to say which it means.
         *
         * @param inputCost  USD per 1,000 prompt tokens
         * @param outputCost USD per 1,000 completion tokens
         * @return this builder
         */
        public ModelBuilder priced(double inputCost, double outputCost) {
            this.inputCost = inputCost;
            this.outputCost = outputCost;
            return this;
        }

        /** @return the model */
        public ModelProfile build() {
            return new ModelProfile(id, provider, providerModelId, outputTokens, temperature,
                                    timeout, role, councilRole, family, contextWindowTokens,
                                    inputCost, outputCost);
        }
    }

    /** Fluent builder for {@link CouncilPolicy}. */
    public static final class PolicyBuilder {

        private final String id;
        private String protocolId = "balanced";
        private List<String> members = List.of("member");
        private String chair = "chair";
        private String validator;
        private int minimumDrafts = 1;
        private int minimumReviews;
        private boolean validationRequired;
        private boolean allowPartial = true;

        private PolicyBuilder(String id) {
            this.id = id;
        }

        /** @param protocolId the protocol to run @return this builder */
        public PolicyBuilder protocol(String protocolId) {
            this.protocolId = protocolId;
            return this;
        }

        /** @param members drafting member ids @return this builder */
        public PolicyBuilder members(String... members) {
            this.members = List.of(members);
            return this;
        }

        /** @param chair the chair model id @return this builder */
        public PolicyBuilder chair(String chair) {
            this.chair = chair;
            return this;
        }

        /**
         * Set the validator and require validation.
         *
         * <p>The two together, because a validator that is never required is a
         * different arrangement from one that is, and tests that set only the
         * first are usually describing the second.
         *
         * @param validator the validator model id
         * @return this builder
         */
        public PolicyBuilder validator(String validator) {
            this.validator = validator;
            this.validationRequired = true;
            return this;
        }

        /**
         * Name a validator without requiring validation to pass.
         *
         * @param validator the validator model id
         * @return this builder
         */
        public PolicyBuilder optionalValidator(String validator) {
            this.validator = validator;
            this.validationRequired = false;
            return this;
        }

        /**
         * @param drafts  drafts that must succeed
         * @param reviews reviews required per draft
         * @return this builder
         */
        public PolicyBuilder quorum(int drafts, int reviews) {
            this.minimumDrafts = drafts;
            this.minimumReviews = reviews;
            return this;
        }

        /** @param allowPartial whether partial results are acceptable @return this builder */
        public PolicyBuilder allowPartial(boolean allowPartial) {
            this.allowPartial = allowPartial;
            return this;
        }

        /** @return the policy */
        public CouncilPolicy build() {
            return new CouncilPolicy(id, protocolId, members, chair, validator,
                                     minimumDrafts, minimumReviews, validationRequired, allowPartial);
        }
    }

    /** Fluent builder for {@link CouncilProfile}. */
    public static final class ProfileBuilder {

        private final String id;
        private String displayName;
        private boolean testOnly;
        private DepthMode defaultDepth = DepthMode.BALANCED;
        private final Map<DepthMode, String> depthPolicies = new LinkedHashMap<>();

        private ProfileBuilder(String id) {
            this.id = id;
            this.displayName = id;
        }

        /** @param displayName human-readable name @return this builder */
        public ProfileBuilder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        /** @param testOnly whether this profile produces fabricated output @return this builder */
        public ProfileBuilder testOnly(boolean testOnly) {
            this.testOnly = testOnly;
            return this;
        }

        /** @param depth the depth applied when a request omits one @return this builder */
        public ProfileBuilder defaultDepth(DepthMode depth) {
            this.defaultDepth = depth;
            return this;
        }

        /**
         * Map one depth to a policy.
         *
         * @param depth    the depth
         * @param policyId the policy to run at it
         * @return this builder
         */
        public ProfileBuilder depth(DepthMode depth, String policyId) {
            depthPolicies.put(depth, policyId);
            return this;
        }

        /**
         * Map every depth to one policy.
         *
         * @param policyId the policy to run at all three depths
         * @return this builder
         */
        public ProfileBuilder allDepths(String policyId) {
            Arrays.stream(DepthMode.values()).forEach(depth -> depthPolicies.put(depth, policyId));
            return this;
        }

        /** @return the profile */
        public CouncilProfile build() {
            Map<DepthMode, String> depths = depthPolicies.isEmpty()
                                            ? Map.of(defaultDepth, id + "-policy")
                                            : depthPolicies;
            return new CouncilProfile(id, displayName, testOnly, defaultDepth, depths);
        }
    }
}
