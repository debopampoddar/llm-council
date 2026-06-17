package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.config.CouncilProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Local development artifact store rooted at `council.persistence.artifactBasePath`.
 */
@Component
public class LocalArtifactStore implements ArtifactStore {
    private final Path basePath;
    private final ObjectMapper objectMapper;

    public LocalArtifactStore(CouncilProperties properties, ObjectMapper objectMapper) {
        this.basePath = Path.of(properties.getPersistence().getArtifactBasePath());
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void writeText(String sessionId, String relativePath, String text) {
        Path target = resolve(sessionId, relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write artifact " + target, ex);
        }
    }

    @Override
    public void writeJson(String sessionId, String relativePath, Object value) {
        Path target = resolve(sessionId, relativePath);
        try {
            Files.createDirectories(target.getParent());
            objectMapper.writeValue(target.toFile(), value);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to write JSON artifact " + target, ex);
        }
    }

    @Override
    public List<String> listArtifacts(String sessionId) {
        Path sessionPath = basePath.resolve(sessionId).normalize();
        if (!Files.exists(sessionPath)) {
            return List.of();
        }
        try (var stream = Files.walk(sessionPath)) {
            return stream.filter(Files::isRegularFile)
                         .map(sessionPath::relativize)
                         .map(Path::toString)
                         .sorted()
                         .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to list artifacts for session " + sessionId, ex);
        }
    }

    private Path resolve(String sessionId, String relativePath) {
        Path sessionPath = basePath.resolve(sessionId).normalize();
        Path target = sessionPath.resolve(relativePath).normalize();
        if (!target.startsWith(sessionPath)) {
            throw new IllegalArgumentException("Artifact path escapes session directory: " + relativePath);
        }
        return target;
    }
}
