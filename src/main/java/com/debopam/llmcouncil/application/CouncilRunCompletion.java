package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.orchestration.CouncilContext;

/**
 * Result delivered when an asynchronous council run finishes.
 */
public record CouncilRunCompletion(
        String sessionId,
        boolean successful,
        CouncilSession session,
        CouncilContext context,
        String failureReason
) {}
