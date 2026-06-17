package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.domain.DepthMode;
import jakarta.validation.constraints.Size;

public record CreateChatRequest(
        String profileId,
        DepthMode depthMode,
        @Size(max = 10000) String initialContext
) {}
