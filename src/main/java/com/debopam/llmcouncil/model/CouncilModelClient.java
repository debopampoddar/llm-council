package com.debopam.llmcouncil.model;

public interface CouncilModelClient {
    ModelCallResult call(ModelCallRequest request);
    ModelHealth health();
}
