package com.debopam.llmcouncil.persistence;

import java.util.List;
import java.util.UUID;

public interface ArtifactStore {
    void writeJson(UUID sessionId, String relativePath, Object value);
    void writeText(UUID sessionId, String relativePath, String value);
    List<String> listArtifacts(UUID sessionId);
}
