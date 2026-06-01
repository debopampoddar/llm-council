package com.debopam.llmcouncil.orchestration;

public interface StageExecutor {
    String stage();
    CouncilContext execute(CouncilContext context);
}
