package com.debopam.llmcouncil.model;

import java.util.List;

public record CouncilProfile(
        String id,
        List<String> memberModelIds,
        String chairModelId,
        String freshEyesModelId,
        String protocolId) {
}
