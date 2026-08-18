package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.api.dto.ModelProbeRequest;
import com.debopam.llmcouncil.api.dto.ModelProbeResponse;

/** Application boundary used by the HTTP model-probe adapter. */
@FunctionalInterface
public interface ModelProbeOperations {
    ModelProbeResponse probe(ModelProbeRequest request);
}
