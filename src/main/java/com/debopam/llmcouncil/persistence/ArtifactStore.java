package com.debopam.llmcouncil.persistence;

import java.util.List;

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
}
