package com.debopam.llmcouncil.export;

import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.persistence.ArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExportPackageService {
    private final ArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    public ExportPackageService(ArtifactStore artifactStore, ObjectMapper objectMapper) {
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ExportManifest export(CouncilContext context, boolean includeRawArtifacts) {
        UUID sessionId = context.session().id();
        List<String> allArtifacts = artifactStore.listArtifacts(sessionId);
        List<String> included = new ArrayList<>();
        List<String> excluded = new ArrayList<>();

        for (String artifact : allArtifacts) {
            if (shouldInclude(artifact, includeRawArtifacts)) {
                included.add(artifact);
            } else {
                excluded.add(artifact);
            }
        }

        ExportManifest manifest = new ExportManifest(
                sessionId,
                context.profile().id(),
                context.protocol().id(),
                Instant.now(),
                context.profile().memberModelIds(),
                List.copyOf(included),
                List.copyOf(excluded),
                includeRawArtifacts,
                includeRawArtifacts ? "none" : "exclude private and raw artifacts",
                "exports/council-export.zip"
        );

        byte[] zip = buildZip(sessionId, manifest, included);
        artifactStore.writeJson(sessionId, "exports/manifest.json", manifest);
        artifactStore.writeBytes(sessionId, "exports/council-export.zip", zip);
        return manifest;
    }

    private boolean shouldInclude(String artifact, boolean includeRawArtifacts) {
        if (artifact.startsWith("exports/")) {
            return false;
        }
        if (includeRawArtifacts) {
            return true;
        }
        return !artifact.startsWith("raw/") && !artifact.startsWith("private/");
    }

    private byte[] buildZip(UUID sessionId, ExportManifest manifest, List<String> includedArtifacts) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                addEntry(zip, "manifest.json", objectMapper.writeValueAsBytes(manifest));
                for (String artifact : includedArtifacts) {
                    addEntry(zip, artifact, artifactStore.readBytes(sessionId, artifact));
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to build export package for session " + sessionId, e);
        }
    }

    private void addEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
