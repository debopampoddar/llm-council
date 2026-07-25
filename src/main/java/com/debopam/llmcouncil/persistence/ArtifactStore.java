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

    /**
     * Remove everything written for one session.
     *
     * <p>Durability moved unbounded growth from RAM to disk; it did not remove
     * it, and artifacts are the largest part of what a run leaves behind. This
     * is called only by the retention sweep, only for a session it has just
     * decided to evict.
     *
     * @param sessionId the session whose artifacts to delete
     * @return {@code true} if anything was removed
     */
    boolean deleteSession(String sessionId);
}
