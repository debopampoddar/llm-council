package com.debopam.llmcouncil.orchestration;

public interface StageExecutor {
    StageType stage();
    CouncilContext execute(CouncilContext context, ProtocolStageOptions options);
}
