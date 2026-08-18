package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.api.dto.ModelProbeErrorResponse;
import com.debopam.llmcouncil.api.dto.ModelProbeRequest;
import com.debopam.llmcouncil.api.dto.ModelProbeResponse;
import com.debopam.llmcouncil.application.ModelProbeRequestException;
import com.debopam.llmcouncil.application.ModelProbeOperations;
import com.debopam.llmcouncil.application.ModelProbeThrottledException;
import com.debopam.llmcouncil.config.user.SecretScanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Iterator;
import java.util.Map;

/** Credential-free, acknowledged HTTP boundary for one bounded model probe. */
@RestController
@RequestMapping("/api/council/config/models")
public class ModelProbeController {

    private static final int MAX_REQUEST_CHARS = 2_000;

    private final ModelProbeOperations probeService;
    private final SecretScanner secretScanner;
    private final ObjectMapper objectMapper;
    private final ObjectReader requestReader;

    public ModelProbeController(ModelProbeOperations probeService,
                                SecretScanner secretScanner,
                                ObjectMapper objectMapper) {
        this.probeService = probeService;
        this.secretScanner = secretScanner;
        this.objectMapper = objectMapper;
        this.requestReader = objectMapper.readerFor(ModelProbeRequest.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @PostMapping(value = "/probe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ModelProbeResponse> probe(@RequestBody(required = false) String body) {
        return ResponseEntity.ok(probeService.probe(readRequest(body)));
    }

    private ModelProbeRequest readRequest(String body) {
        if (body == null || body.isBlank()) {
            throw new ModelProbeRequestException("A JSON request body is required.",
                                                 "Choose a provider and enter its exact model id.");
        }
        if (body.length() > MAX_REQUEST_CHARS) {
            throw new ModelProbeRequestException("The probe request is too large.",
                                                 "Send only provider, providerModelId, and acknowledgeCloudCall.");
        }
        if (!secretScanner.scanValues(body).isEmpty()) {
            throw credentialsRefused();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw malformed();
            }
            if (containsCredentialField(root)) {
                throw credentialsRefused();
            }
            return requestReader.readValue(body);
        } catch (ModelProbeRequestException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw malformed();
        }
    }

    private boolean containsCredentialField(JsonNode node) {
        if (node == null || !node.isContainerNode()) return false;
        if (node.isArray()) {
            for (JsonNode child : node) if (containsCredentialField(child)) return true;
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (secretScanner.isCredentialFieldName(field.getKey())
                    || containsCredentialField(field.getValue())) return true;
        }
        return false;
    }

    private ModelProbeRequestException credentialsRefused() {
        return new ModelProbeRequestException(
                "Credentials are not accepted by the model-probe endpoint.",
                "Set the provider credential in the environment and restart the application.");
    }

    private ModelProbeRequestException malformed() {
        return new ModelProbeRequestException(
                "The probe request is not valid strict JSON.",
                "Send only provider, providerModelId, and acknowledgeCloudCall.");
    }

    @ExceptionHandler(ModelProbeRequestException.class)
    public ResponseEntity<ModelProbeErrorResponse> handleRequest(ModelProbeRequestException ex) {
        return ResponseEntity.badRequest()
                .body(new ModelProbeErrorResponse(ex.getMessage(), ex.remediation()));
    }

    @ExceptionHandler(ModelProbeThrottledException.class)
    public ResponseEntity<ModelProbeErrorResponse> handleThrottled(ModelProbeThrottledException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(ex.retryAfterSeconds()))
                .body(new ModelProbeErrorResponse(ex.getMessage(),
                        "Wait " + ex.retryAfterSeconds() + " seconds before running another probe."));
    }
}
