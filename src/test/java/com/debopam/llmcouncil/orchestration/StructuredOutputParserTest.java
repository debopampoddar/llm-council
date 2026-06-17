package com.debopam.llmcouncil.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputParserTest {

    private final StructuredOutputParser parser = new StructuredOutputParser(new ObjectMapper());

    @Test
    void parsesReviewEnvelope() {
        String json = """
                {
                  "reviews": [{
                    "draftId": "draft-A",
                    "strengths": ["clear"],
                    "issues": ["thin evidence"],
                    "criteria": [{"name": "accuracy", "score": 82, "rationale": "reasonable"}],
                    "overallScore": 80,
                    "confidence": 0.7
                  }]
                }
                """;

        StructuredOutputParser.ReviewEnvelope envelope = parser.parseReviews(json);

        assertEquals(1, envelope.reviews().size());
        assertEquals("draft-A", envelope.reviews().getFirst().draftId());
        assertEquals(80, envelope.reviews().getFirst().overallScore());
    }

    @Test
    void rejectsOutOfRangeReviewScore() {
        String json = """
                {"reviews": [{
                  "draftId": "draft-A",
                  "criteria": [],
                  "overallScore": 120,
                  "confidence": 0.7
                }]}
                """;

        assertThrows(IllegalArgumentException.class, () -> parser.parseReviews(json));
    }

    @Test
    void parsesValidationEnvelope() {
        String json = """
                {
                  "approved": true,
                  "confidence": 0.9,
                  "issues": [],
                  "recommendedFixes": [],
                  "criteria": {"correctness": "pass"},
                  "requiresHumanReview": false
                }
                """;

        StructuredOutputParser.ValidationEnvelope validation = parser.parseValidation(json);

        assertTrue(validation.approved());
        assertEquals(0.9, validation.confidence());
    }
}
