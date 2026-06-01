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
import java.util.UUID;
import java.util.stream.Stream;

@Component
public class FileArtifactStore implements ArtifactStore {
    private final Path runsDir;
    private final ObjectMapper objectMapper;

    public FileArtifactStore(CouncilProperties properties, ObjectMapper objectMapper) {
        this.runsDir = Path.of(properties.runsDir());
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void writeJson(UUID sessionId, String relativePath, Object value) {
        Path target = resolve(sessionId, relativePath);
        try {
            Files.createDirectories(target.getParent());
            objectMapper.writeValue(target.toFile(), value);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write JSON artifact " + target, e);
        }
    }

    @Override
    public void writeText(UUID sessionId, String relativePath, String value) {
        Path target = resolve(sessionId, relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, value == null ? "" : value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write text artifact " + target, e);
        }
    }

    @Override
    public List<String> listArtifacts(UUID sessionId) {
        Path sessionDir = runsDir.resolve(sessionId.toString());
        if (!Files.exists(sessionDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(sessionDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(sessionDir::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list artifacts for session " + sessionId, e);
        }
    }

    private Path resolve(UUID sessionId, String relativePath) {
        Path normalized = Path.of(relativePath).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Artifact path must stay inside the session directory");
        }
        return runsDir.resolve(sessionId.toString()).resolve(normalized);
    }
}
