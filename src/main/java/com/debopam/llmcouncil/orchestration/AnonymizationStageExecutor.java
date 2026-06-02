package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.debopam.llmcouncil.orchestration.StageType.ANONYMIZE;

@Component
public class AnonymizationStageExecutor implements StageExecutor {
    private final ArtifactStore artifactStore;
    private final EventPublisher events;

    public AnonymizationStageExecutor(ArtifactStore artifactStore, EventPublisher events) {
        this.artifactStore = artifactStore;
        this.events = events;
    }

    @Override
    public StageType stage() {
        return ANONYMIZE;
    }

    @Override
    public CouncilContext execute(CouncilContext context, ProtocolStageOptions options) {
        long seed = context.session().id().getLeastSignificantBits();
        List<Draft> shuffled = new ArrayList<>(context.drafts());
        Collections.shuffle(shuffled, new Random(seed));

        List<AnonymizedDraft> anonymized = new ArrayList<>();
        Map<String, String> hiddenMap = new LinkedHashMap<>();
        for (int i = 0; i < shuffled.size(); i++) {
            String draftId = "draft-" + (char) ('A' + i);
            Draft draft = shuffled.get(i);
            anonymized.add(new AnonymizedDraft(draftId, draft.content()));
            hiddenMap.put(draftId, draft.modelId());
        }

        AnonymizedDraftSet draftSet = new AnonymizedDraftSet(List.copyOf(anonymized), Map.copyOf(hiddenMap), seed);
        context.setAnonymizedDraftSet(draftSet);
        artifactStore.writeJson(context.session().id(), "private/anonymization-map.json", hiddenMap);
        artifactStore.writeJson(context.session().id(), "normalized/anonymized-drafts.json", anonymized);
        events.publish(context.session().id(), stage().name(), "DRAFT_ANONYMIZED", null, Map.of("draftCount", anonymized.size()));
        return context;
    }
}
