package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.config.CouncilProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ProtocolDefinitionRegistry {
    private final Map<String, ProtocolDefinition> protocols;

    public ProtocolDefinitionRegistry(CouncilProperties properties) {
        this.protocols = properties.protocols().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> toDefinition(entry.getKey(), entry.getValue())
                ));
    }

    public ProtocolDefinition get(String protocolId) {
        String id = protocolId == null || protocolId.isBlank() ? "balanced" : protocolId;
        ProtocolDefinition definition = protocols.get(id);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown council protocol " + id);
        }
        return definition;
    }

    public List<ProtocolDefinition> all() {
        return protocols.values().stream()
                .sorted(java.util.Comparator.comparing(ProtocolDefinition::id))
                .toList();
    }

    private ProtocolDefinition toDefinition(String id, CouncilProperties.ProtocolConfig config) {
        List<StageType> stages = config.orderedStages().stream()
                .map(StageType::valueOf)
                .toList();

        Map<StageType, ProtocolStageOptions> options = new EnumMap<>(StageType.class);
        if (config.stageOptions() != null) {
            config.stageOptions().forEach((stage, stageConfig) ->
                    options.put(StageType.valueOf(stage), toOptions(stageConfig))
            );
        }

        return new ProtocolDefinition(id, config.description(), stages, Map.copyOf(options));
    }

    private ProtocolStageOptions toOptions(CouncilProperties.StageOptionsConfig config) {
        return new ProtocolStageOptions(
                config.reviewMode(),
                config.maxRounds(),
                config.debateTriggerScoreVariance(),
                config.debateTriggerDissentCount(),
                config.forceRun(),
                config.preserveDissent(),
                config.exportRawArtifacts(),
                config.artifactLabel()
        );
    }
}
