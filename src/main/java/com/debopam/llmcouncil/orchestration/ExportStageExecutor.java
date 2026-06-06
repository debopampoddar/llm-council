/**
 * Auto-generated documentation for ExportStageExecutor.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.application.EventPublisher;
import com.debopam.llmcouncil.export.ExportManifest;
import com.debopam.llmcouncil.export.ExportPackageService;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ExportStageExecutor implements StageExecutor {
    private final ExportPackageService exportPackageService;
    private final EventPublisher events;

    public ExportStageExecutor(ExportPackageService exportPackageService, EventPublisher events) {
        this.exportPackageService = exportPackageService;
        this.events = events;
    }

    @Override
    public StageType stage() {
        return StageType.EXPORT;
    }

    /*
    Export policy:

    Default rigorous export excludes raw/ and private/.
    Keep raw prompt/response export as an admin-only option.
    Never include API keys, provider tokens, or local auth files.
     */

    @Override
    public CouncilContext execute(CouncilContext context, ProtocolStageOptions options) {
        boolean includeRawArtifacts = options.exportRawArtifactsOrDefault(false);
        ExportManifest manifest = exportPackageService.export(context, includeRawArtifacts);
        context.setExportManifest(manifest);
        events.publish(context.session().id(), stage().name(), "EXPORT_COMPLETED", null, Map.of(
                "exportPath", manifest.exportPath(),
                "includedArtifacts", manifest.includedArtifacts().size(),
                "excludedArtifacts", manifest.excludedArtifacts().size()
        ));
        return context;
    }
}
