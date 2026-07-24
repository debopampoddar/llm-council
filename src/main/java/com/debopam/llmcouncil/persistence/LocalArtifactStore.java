package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Local development artifact store rooted at `council.persistence.artifactBasePath`.
 */
@Component
public class LocalArtifactStore implements ArtifactStore {
    private final Path basePath;
    private final ObjectMapper objectMapper;

    /**
     * @param catalogHolder supplies the resolved artifact path, so a user
     *                      overlay's {@code artifactBasePath} takes effect
     * @param objectMapper  used to serialise JSON artifacts
     */
    public LocalArtifactStore(CouncilCatalogHolder catalogHolder, ObjectMapper objectMapper) {
        this.basePath = Path.of(catalogHolder.get().runtime().artifactBasePath());
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

    @Override
    public Optional<String> readArtifact(String sessionId, String relativePath) {
        Path target = resolve(sessionId, relativePath);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read artifact " + target, ex);
        }
    }

    /**
     * Resolve an artifact path inside a session directory.
     *
     * <p>Both paths are made absolute before comparison. Normalising a relative
     * base would let a crafted {@code relativePath} climb out of it, so the
     * containment check is only meaningful on absolute, normalised paths.
     *
     * @param sessionId    the session the artifact belongs to
     * @param relativePath the artifact path relative to the session directory
     * @return the resolved absolute path
     * @throws IllegalArgumentException if the resolved path escapes the session directory
     */
    private Path resolve(String sessionId, String relativePath) {
        Path sessionPath = basePath.resolve(sessionId).toAbsolutePath().normalize();
        Path target = sessionPath.resolve(relativePath).toAbsolutePath().normalize();
        if (!target.startsWith(sessionPath)) {
            throw new IllegalArgumentException("Artifact path escapes session directory: " + relativePath);
        }
        return target;
    }
}
