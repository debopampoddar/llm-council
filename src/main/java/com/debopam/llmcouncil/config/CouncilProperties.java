// CouncilProperties.java
package com.debopam.llmcouncil.config;

import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "council")
public class CouncilProperties {
    private List<ModelProps> models = new ArrayList<>();
    private Map<String, ProfileProps> profiles = new LinkedHashMap<>();
    private Map<String, PolicyProps> policies = new LinkedHashMap<>();
    private Map<String, ProtocolProps> protocols = new LinkedHashMap<>();
    private PersistenceProps persistence = new PersistenceProps();
    private boolean allowMockFallback = false;

    // Getters/setters
    public List<ModelProps> getModels() { return models; }
    public void setModels(List<ModelProps> m) { this.models = m; }
    public Map<String, ProfileProps> getProfiles() { return profiles; }
    public void setProfiles(Map<String, ProfileProps> p) { this.profiles = p; }
    public Map<String, PolicyProps> getPolicies() { return policies; }
    public void setPolicies(Map<String, PolicyProps> p) { this.policies = p; }
    public Map<String, ProtocolProps> getProtocols() { return protocols; }
    public void setProtocols(Map<String, ProtocolProps> p) { this.protocols = p; }
    public PersistenceProps getPersistence() { return persistence; }
    public void setPersistence(PersistenceProps p) { this.persistence = p; }
    public boolean isAllowMockFallback() { return allowMockFallback; }
    public void setAllowMockFallback(boolean allowMockFallback) { this.allowMockFallback = allowMockFallback; }

    public static class ModelProps {
        private String id, provider, providerModelId;
        private int defaultOutputTokens = 2000;
        private double temperature = 0.3;
        private int timeoutSeconds = 120;
        private ModelRole role = ModelRole.MEMBER;
        private boolean testOnly = false;

        /** Maximum number of retry attempts for transient failures (0 = no retries). */
        private int retryMaxAttempts = 2;

        /** Base delay in milliseconds before the first retry; doubled on each subsequent attempt. */
        private long retryBaseDelayMs = 1000L;

        // (Adversarial Roles): debate persona for this model. 
        // PROPOSER is the default; CRITIC models get adversarial prompts.
        private CouncilRole councilRole = CouncilRole.PROPOSER;

        // (Model Heterogeneity): architecture family tag. 
        // Used by the configuration validator to warn when all council members
        // share the same model family (e.g., all "llama" or all "gpt").
        private String modelFamily;

        public String getId() { return id; } public void setId(String v) { id = v; }
        public String getProvider() { return provider; } public void setProvider(String v) { provider = v; }
        public String getProviderModelId() { return providerModelId; } public void setProviderModelId(String v) { providerModelId = v; }
        public int getDefaultOutputTokens() { return defaultOutputTokens; } public void setDefaultOutputTokens(int v) { defaultOutputTokens = v; }
        public double getTemperature() { return temperature; } public void setTemperature(double v) { temperature = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; } public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
        public ModelRole getRole() { return role; } public void setRole(ModelRole v) { role = v; }
        public boolean isTestOnly() { return testOnly; } public void setTestOnly(boolean v) { testOnly = v; }
        public int getRetryMaxAttempts() { return retryMaxAttempts; } public void setRetryMaxAttempts(int v) { retryMaxAttempts = v; }
        public long getRetryBaseDelayMs() { return retryBaseDelayMs; } public void setRetryBaseDelayMs(long v) { retryBaseDelayMs = v; }
        public CouncilRole getCouncilRole() { return councilRole; } public void setCouncilRole(CouncilRole v) { councilRole = v; }
        public String getModelFamily() { return modelFamily; } public void setModelFamily(String v) { modelFamily = v; }
    }

    public static class ProfileProps {
        private String displayName;
        private boolean testOnly = false;
        private DepthMode defaultDepth = DepthMode.BALANCED;
        private Map<String, String> depthPolicies = new LinkedHashMap<>();
        public String getDisplayName() { return displayName; } public void setDisplayName(String v) { displayName = v; }
        public boolean isTestOnly() { return testOnly; } public void setTestOnly(boolean v) { testOnly = v; }
        public DepthMode getDefaultDepth() { return defaultDepth; } public void setDefaultDepth(DepthMode v) { defaultDepth = v; }
        public Map<String, String> getDepthPolicies() { return depthPolicies; } public void setDepthPolicies(Map<String, String> v) { depthPolicies = v; }
    }

    public static class PolicyProps {
        private String protocolId;
        private List<String> memberModelIds = new ArrayList<>();
        private String chairModelId;
        private String validatorModelId;
        private int minimumSuccessfulDrafts = 1;
        private int minimumReviewsPerDraft = 0;
        private boolean validationRequired = false;
        private boolean allowPartial = true;

        /**
         * Suppress the boot warning when this policy's validator is not
         * independent of its chair. The independence tier is still reported on
         * every run — this only acknowledges that the trade-off is deliberate,
         * typically on hardware that cannot host a second model family.
         */
        private boolean acknowledgeSelfValidation = false;
        public String getProtocolId() { return protocolId; } public void setProtocolId(String v) { protocolId = v; }
        public List<String> getMemberModelIds() { return memberModelIds; } public void setMemberModelIds(List<String> v) { memberModelIds = v; }
        public String getChairModelId() { return chairModelId; } public void setChairModelId(String v) { chairModelId = v; }
        public String getValidatorModelId() { return validatorModelId; } public void setValidatorModelId(String v) { validatorModelId = v; }
        public int getMinimumSuccessfulDrafts() { return minimumSuccessfulDrafts; } public void setMinimumSuccessfulDrafts(int v) { minimumSuccessfulDrafts = v; }
        public int getMinimumReviewsPerDraft() { return minimumReviewsPerDraft; } public void setMinimumReviewsPerDraft(int v) { minimumReviewsPerDraft = v; }
        public boolean isValidationRequired() { return validationRequired; } public void setValidationRequired(boolean v) { validationRequired = v; }
        public boolean isAllowPartial() { return allowPartial; } public void setAllowPartial(boolean v) { allowPartial = v; }
        public boolean isAcknowledgeSelfValidation() { return acknowledgeSelfValidation; }
        public void setAcknowledgeSelfValidation(boolean v) { acknowledgeSelfValidation = v; }
    }

    public static class ProtocolProps {
        private String description;
        private List<String> orderedStages = new ArrayList<>();
        private Map<String, Map<String, Object>> stageOptions = new LinkedHashMap<>();
        public String getDescription() { return description; } public void setDescription(String v) { description = v; }
        public List<String> getOrderedStages() { return orderedStages; } public void setOrderedStages(List<String> v) { orderedStages = v; }
        public Map<String, Map<String, Object>> getStageOptions() { return stageOptions; } public void setStageOptions(Map<String, Map<String, Object>> v) { stageOptions = v; }
    }

    public static class PersistenceProps {
        private String artifactBasePath = System.getProperty("user.home") + "/.llm-council/artifacts";
        public String getArtifactBasePath() { return artifactBasePath; } public void setArtifactBasePath(String v) { artifactBasePath = v; }
    }
}
