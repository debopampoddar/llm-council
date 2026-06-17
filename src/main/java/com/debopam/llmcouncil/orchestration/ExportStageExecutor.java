// ── ExportStageExecutor.java ──────────────────────────────────────────────
package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * EXPORT stage: packages all artifacts for download.
 * Delegates to ExportPackageService (wire in when needed).
 */
@Component
public class ExportStageExecutor implements StageExecutor {
    private final EventPublisher events;
    private final ArtifactStore artifactStore;

    public ExportStageExecutor(EventPublisher events, ArtifactStore artifactStore) {
        this.events = events; this.artifactStore = artifactStore;
    }

    @Override public StageType stage() { return StageType.EXPORT; }

    @Override
    public CouncilContext execute(CouncilContext ctx, ProtocolStageOptions opts) {
        boolean exportRaw = opts.getBoolean("export-raw-artifacts", false);
        List<String> artifacts = artifactStore.listArtifacts(ctx.session().id());
        artifactStore.writeJson(ctx.session().id(), "exports/manifest.json",
                                Map.of("includeRawArtifacts", exportRaw,
                                       "artifacts", artifacts.stream()
                                                .filter(a -> exportRaw || (!a.startsWith("raw/") && !a.startsWith("private/")))
                                                .toList()));
        events.publish(ctx.session().id(), stage().name(), "EXPORT_COMPLETED", null,
                       Map.of("exportRaw", exportRaw,
                              "draftCount", ctx.drafts().size(),
                              "reviewCount", ctx.reviews().size(),
                              "debateRounds", ctx.debateRounds().size(),
                              "artifactCount", artifacts.size()));
        return ctx;
    }
}
