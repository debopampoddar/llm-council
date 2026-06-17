package com.debopam.llmcouncil.model; public interface ModelClient { ModelCallResult call(ModelCallRequest request) throws ModelCallException; }
