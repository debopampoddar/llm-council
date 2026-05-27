package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface EventPublisher {
    CouncilEvent publish(UUID sessionId, String stage, String type, String modelId, Map<String, Object> payload);
    List<CouncilEvent> history(UUID sessionId);
    SseEmitter subscribe(UUID sessionId);
}
