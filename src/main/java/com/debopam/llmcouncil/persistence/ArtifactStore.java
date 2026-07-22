package com.debopam.llmcouncil.persistence;

import java.util.List;
import java.util.Optional;

/**
 * Stores prompts, raw responses, normalized artifacts, and export bundles.
 *
 * <p>This local-file contract keeps artifacts inspectable during development.
 * A production implementation can move metadata to a database and large blobs
 * to object storage without changing orchestration logic.
 */
public interface ArtifactStore {
    void writeText(String sessionId, String relativePath, String text);

    void writeJson(String sessionId, String relativePath, Object value);

    List<String> listArtifacts(String sessionId);

    /**
     * Read one artifact's content.
     *
     * @param sessionId    the session the artifact belongs to
     * @param relativePath the artifact path relative to the session directory
     * @return the artifact content, or empty when no such artifact exists
     * @throws IllegalArgumentException if the path escapes the session directory
     */
    Optional<String> readArtifact(String sessionId, String relativePath);
}
