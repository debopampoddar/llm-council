package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBudgetTest {

    @Test
    void unlimitedBudgetReturnsSectionsUntouched() {
        Map<String, List<String>> sections = sections("drafts", List.of("a".repeat(100_000)));

        Map<String, List<String>> fitted = PromptBudget.unlimited().fit(0, sections);

        assertSame(sections, fitted);
        assertFalse(PromptBudget.unlimited().truncated());
    }

    @Test
    void contentThatFitsPassesThroughByteIdentical() {
        PromptBudget budget = PromptBudget.forModel(model(8192, 1000));
        Map<String, List<String>> sections = sections("drafts", List.of("short draft", "another"));

        Map<String, List<String>> fitted = budget.fit(500, sections);

        assertEquals(sections, fitted);
        assertFalse(budget.truncated(), "nothing should be reported when nothing was cut");
    }

    @Test
    void oversizedContentIsTruncatedAndReported() {
        PromptBudget budget = PromptBudget.forModel(model(4096, 1200));
        // Three 1200-token drafts cannot fit alongside a 1200 token reservation
        // in a 4096 window. This is the shipped local-rigorous shape.
        Map<String, List<String>> sections = sections("drafts",
                List.of("x".repeat(5_000), "y".repeat(5_000), "z".repeat(5_000)));

        Map<String, List<String>> fitted = budget.fit(1_400, sections);

        int total = fitted.get("drafts").stream().mapToInt(String::length).sum();
        assertTrue(total < 15_000, "content should have been reduced");
        assertTrue(total <= budget.totalChars(), "result must fit the budget");
        assertTrue(budget.truncated());
        assertTrue(budget.notices().getFirst().contains("truncated evidence"),
                   "the notice must say the answer is built from partial evidence");
    }

    @Test
    void truncationIsMarkedSoDownstreamModelsSeeItIsAFragment() {
        PromptBudget budget = PromptBudget.forModel(model(4096, 500));
        Map<String, List<String>> sections = sections("drafts", List.of("x".repeat(40_000)));

        String fitted = budget.fit(0, sections).get("drafts").getFirst();

        assertTrue(fitted.contains("truncated") || fitted.contains("omitted"),
                   "a fragment presented as complete would be reasoned over as complete");
    }

    @Test
    void oneHugeItemDoesNotStarveTheOthers() {
        PromptBudget budget = PromptBudget.forModel(model(4096, 500));
        Map<String, List<String>> sections = sections("drafts",
                List.of("huge".repeat(20_000), "small draft that should survive intact"));

        List<String> fitted = budget.fit(0, sections).get("drafts");

        assertEquals("small draft that should survive intact", fitted.get(1),
                     "a short draft must not be cut to make room for a long one");
        assertTrue(fitted.getFirst().length() < 80_000);
    }

    @Test
    void smallSectionsKeepTheirContentWhileLargeOnesAreCut() {
        PromptBudget budget = PromptBudget.forModel(model(4096, 500));
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put("drafts", List.of("d".repeat(30_000)));
        sections.put("scores", List.of("draft-a total=91.2"));

        Map<String, List<String>> fitted = budget.fit(0, sections);

        // Fair-share must not hand the tiny score section half the window and
        // then truncate the drafts against the other half.
        assertEquals("draft-a total=91.2", fitted.get("scores").getFirst());
        assertTrue(fitted.get("drafts").getFirst().length() > 5_000,
                   "drafts should receive the surplus the scores section did not need");
    }

    @Test
    void modelWithNoDeclaredWindowDisablesBudgeting() {
        assertTrue(PromptBudget.forModel(model(0, 1000)).isUnlimited());
        assertTrue(PromptBudget.forModel(null).isUnlimited());
    }

    @Test
    void windowSmallerThanReservedOutputIsReportedRatherThanNegative() {
        // defaultOutputTokens above the context window is a misconfiguration that
        // would otherwise produce a negative budget and nonsense downstream.
        PromptBudget budget = PromptBudget.forModel(model(1024, 4000));

        assertEquals(0, budget.totalChars());
        assertTrue(budget.truncated());
        assertTrue(budget.notices().getFirst().contains("no room for a prompt"));
    }

    @Test
    void budgetShrinksAsReservedOutputGrows() {
        int small = PromptBudget.forModel(model(8192, 500)).totalChars();
        int large = PromptBudget.forModel(model(8192, 4000)).totalChars();

        assertTrue(large < small, "reserving more output must leave less room for the prompt");
    }

    private Map<String, List<String>> sections(String name, List<String> items) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        sections.put(name, items);
        return sections;
    }

    private ModelProfile model(int contextWindowTokens, int outputTokens) {
        return new ModelProfile("test-model", "ollama", "llama3.1:8b", outputTokens, 0.3,
                                Duration.ofSeconds(60), ModelRole.MEMBER,
                                CouncilRole.PROPOSER, "llama", contextWindowTokens);
    }
}
