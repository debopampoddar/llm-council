/**
 * Auto-generated documentation for ExportPackageServiceTest.java.
 * Part of the llm-council Java implementation of multi-LLM deliberation.
 */

package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.config.CouncilProperties;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.export.ExportManifest;
import com.debopam.llmcouncil.export.ExportPackageService;
import com.debopam.llmcouncil.model.CouncilProfile;
import com.debopam.llmcouncil.persistence.FileArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportPackageServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void excludesRawAndPrivateArtifactsByDefault() {
        CouncilProperties properties = new CouncilProperties(tempDir.toString(), null, Map.of(), Map.of(), Map.of(), Map.of());
        FileArtifactStore store = new FileArtifactStore(properties, new ObjectMapper());
        ExportPackageService service = new ExportPackageService(store, new ObjectMapper());
        CouncilSession session = CouncilSession.created(UUID.randomUUID(), "question", "context", "profile", DepthMode.RIGOROUS);
        CouncilProfile profile = new CouncilProfile("profile", List.of("m1"), "chair", "validator", "rigorous");
        CouncilContext context = new CouncilContext(session, profile, new ProtocolDefinition("rigorous", "test", List.of(StageType.EXPORT), Map.of()));

        store.writeText(session.id(), "raw/review.json", "raw");
        store.writeText(session.id(), "private/anonymization-map.json", "private");
        store.writeText(session.id(), "final/answer.md", "answer");

        ExportManifest manifest = service.export(context, false);

        assertTrue(manifest.includedArtifacts().contains("final/answer.md"));
        assertFalse(manifest.includedArtifacts().contains("raw/review.json"));
        assertFalse(manifest.includedArtifacts()
                            .contains("private/anonymization-map.json"));
        assertTrue(Files.exists(tempDir.resolve(session.id().toString())
                                       .resolve("exports/council-export.zip")));
    }
}
