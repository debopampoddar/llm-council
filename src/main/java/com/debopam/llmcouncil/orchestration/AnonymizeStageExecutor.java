// ── AnonymizeStageExecutor.java 
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ANONYMIZE stage: marks all drafts as anonymous so reviewing models cannot
 * identify which model wrote which answer, preventing positional bias.
 * Based on the anonymized peer review pattern from Karpathy's LLM Council.
 */
@Component
public class AnonymizeStageExecutor implements StageExecutor {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ArtifactStore artifactStore;

    public AnonymizeStageExecutor(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    @Override public StageType stage() { return StageType.ANONYMIZE; }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) {
        List<Draft> anonymised = new ArrayList<>();
        Map<String, Map<String, String>> hiddenMap = new LinkedHashMap<>();
        for (Draft draft : ctx.drafts()) {
            String anonymousId = nextDraftId();
            hiddenMap.put(anonymousId, Map.of("originalDraftId", draft.draftId(),
                                              "modelId", draft.modelId()));
            anonymised.add(new Draft(anonymousId, draft.modelId(), draft.text(), true));
        }
        ctx.clearDrafts();
        anonymised.forEach(ctx::addDraft);
        artifactStore.writeJson(ctx.session().id(), "private/anonymization-map.json", hiddenMap);
        artifactStore.writeJson(ctx.session().id(), "normalized/anonymized-drafts.json", anonymised);
        return ctx;
    }

    private String nextDraftId() {
        return "draft-" + Integer.toHexString(RANDOM.nextInt()).replace("-", "").toUpperCase();
    }
}
