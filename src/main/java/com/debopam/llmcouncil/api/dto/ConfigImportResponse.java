package com.debopam.llmcouncil.api.dto;

import com.debopam.llmcouncil.config.user.UserConfigDocument;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The result of importing a shared configuration.
 *
 * <p>Import validates and returns; it never writes. Someone else's configuration
 * is exactly the case where a user should see what they are taking on before it
 * replaces their own — which models it binds, which profiles it redefines, and
 * whether it weakens any guarantee — so the confirmation step is
 * {@code PUT /draft} with the document below.
 *
 * <p>The parsed document travels back with the report so the caller does not have
 * to convert the YAML it submitted into the JSON the save endpoint takes. Without
 * it, every client would need a YAML parser to complete a round trip the server
 * has already done.
 *
 * @param document   the imported configuration, parsed but not sanitised
 * @param validation what validating it found
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigImportResponse(
        UserConfigDocument document,
        ValidationReportResponse validation
) {}
