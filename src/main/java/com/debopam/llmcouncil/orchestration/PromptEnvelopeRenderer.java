package com.debopam.llmcouncil.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes the data portion of every council prompt with explicit provenance.
 *
 * <p>Free-form XML-like delimiters are not a security boundary: input can contain
 * the same closing tag and visually escape the section. JSON string escaping
 * keeps attacker-controlled text inside a data field, while the accompanying
 * authority labels tell every stage which field may define the task and which
 * fields are evidence only. Models can still make mistakes, so this renderer is
 * one layer of the trust-boundary design rather than a claim of perfect prompt
 * injection prevention.
 */
final class PromptEnvelopeRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PromptEnvelopeRenderer() {
    }

    static String render(String question, String context) {
        return render(question, context, Map.of());
    }

    static String render(String question, String context, Map<String, ?> artifacts) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("task", Map.of(
                "text", question == null ? "" : question,
                "instructionAuthority", "USER_TASK"));
        envelope.put("supportingContext", Map.of(
                "text", context == null ? "" : context,
                "instructionAuthority", "NONE",
                "trust", "UNTRUSTED_DATA"));
        artifacts.forEach(envelope::put);
        try {
            return JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize prompt data envelope", ex);
        }
    }

    static Map<String, Object> untrustedArtifact(String type, String id, String text) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("type", type);
        if (id != null && !id.isBlank()) {
            artifact.put("id", id);
        }
        artifact.put("text", text == null ? "" : text);
        artifact.put("instructionAuthority", "NONE");
        artifact.put("trust", "UNTRUSTED_MODEL_OUTPUT");
        return artifact;
    }
}
