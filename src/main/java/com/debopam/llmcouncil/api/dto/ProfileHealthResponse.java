package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.domain.DepthMode;

import java.util.List;

public record ProfileHealthResponse(
        String profileId,
        DepthMode depthMode,
        String policyId,
        String protocolId,
        boolean runnable,
        List<ModelHealthResponse> models,
        List<String> warnings
) {}
