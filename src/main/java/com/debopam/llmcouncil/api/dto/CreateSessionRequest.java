package com.debopam.llmcouncil.api.dto;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.domain.ContextPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record CreateSessionRequest(
        @NotBlank @Size(max = 5000) String question,
        @Size(max = 10000) String context,
        ContextPurpose contextPurpose,
        DepthMode depthMode,
        String profileId
) {}
