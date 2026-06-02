package com.debopam.llmcouncil.persistence;

import java.util.List;
import java.util.UUID;

public interface ArtifactStore {
    void writeJson(UUID sessionId, String relativePath, Object value);
    void writeText(UUID sessionId, String relativePath, String value);
    void writeBytes(UUID sessionId, String relativePath, byte[] value);
    byte[] readBytes(UUID sessionId, String relativePath);
    List<String> listArtifacts(UUID sessionId);
}
