package com.debopam.llmcouncil.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void combinesSeparateReviewEnvelopesSurroundedByModelProse() {
        String output = """
                Here are the reviews for each draft:

                **Draft A**
                {"reviews":[{"draftId":"draft-A","criteria":[],"overallScore":80,"confidence":0.8}]}

                **Draft B**
                {"reviews":[{"draftId":"draft-B","issues":["Use {idempotency} keys"],
                              "criteria":[],"overallScore":85,"confidence":0.9}]}

                **Draft C**
                {"reviews":[{"draftId":"draft-C","criteria":[],"overallScore":75,"confidence":0.7}]}
                """;

        StructuredOutputParser.ReviewEnvelope envelope = parser.parseReviews(output);

        assertEquals(List.of("draft-A", "draft-B", "draft-C"),
                envelope.reviews().stream().map(StructuredOutputParser.ReviewJson::draftId).toList());
        assertTrue(envelope.diagnostics().isEmpty());
    }

    @Test
    void acceptsCompactCriteriaObjectProducedByLocalModels() {
        String output = """
                {"reviews":[{
                  "draftId":"draft-A",
                  "criteria":{"accuracy":90,"completeness":80,"reasoning":85},
                  "overallScore":85,
                  "confidence":0.8
                }]}
                """;

        StructuredOutputParser.ReviewJson review = parser.parseReviews(output).reviews().getFirst();

        assertEquals(List.of("accuracy", "completeness", "reasoning"),
                review.criteria().stream().map(CriterionScore::name).toList());
        assertEquals(List.of(90, 80, 85),
                review.criteria().stream().map(CriterionScore::score).toList());
    }

    @Test
    void normalizesFractionalScoresProducedByLocalModels() {
        String output = """
                {"reviews":[{
                  "draftId":"draft-A",
                  "criteria":[{"name":"accuracy","score":89.5,"rationale":"close"}],
                  "overallScore":83.6,
                  "confidence":0.85
                }]}
                """;

        StructuredOutputParser.ReviewJson review = parser.parseReviews(output).reviews().getFirst();

        assertEquals(84, review.overallScore());
        assertEquals(90, review.criteria().getFirst().score());
        assertTrue(parser.parseReviews(output).diagnostics().isEmpty());
    }

    @Test
    void retainsValidReviewsWhenOneSiblingIsMalformed() {
        String output = """
                {"reviews":[
                  {"draftId":"draft-good","criteria":[],"overallScore":82,"confidence":0.8},
                  {"draftId":"draft-bad","criteria":[],"overallScore":999,"confidence":0.8}
                ]}
                """;

        StructuredOutputParser.ReviewEnvelope envelope = parser.parseReviews(output);

        assertEquals(1, envelope.reviews().size());
        assertEquals("draft-good", envelope.reviews().getFirst().draftId());
        assertEquals(1, envelope.diagnostics().size());
        assertTrue(envelope.diagnostics().getFirst().contains("Review 2"));
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
