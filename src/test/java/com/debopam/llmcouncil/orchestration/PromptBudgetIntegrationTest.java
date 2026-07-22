package com.debopam.llmcouncil.orchestration;

import com.debopam.llmcouncil.model.CouncilRole;
import com.debopam.llmcouncil.model.ModelProfile;
import com.debopam.llmcouncil.model.ModelRole;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies budgeting through {@link PromptBuilder} rather than in isolation.
 *
 * <p>The unit tests prove the allocator is correct; these prove it is actually
 * connected — that a real synthesis prompt shrinks, that instructions survive,
 * and that the existing unbudgeted call paths still behave as before.
 */
class PromptBudgetIntegrationTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    void synthesisPromptShrinksToFitASmallLocalWindow() {
        // The shipped local-rigorous shape: three members at 1200 output tokens
        // feeding a chair with a 4096 token window.
        List<Draft> drafts = List.of(draft("a"), draft("b"), draft("c"));
        PromptBudget budget = PromptBudget.forModel(chair(4096, 1800));

        String unbudgeted = userContent(promptBuilder.synthesisMessages(
                "What should we do?", null, drafts, List.of(), List.of(), List.of(), true));
        String budgeted = userContent(promptBuilder.synthesisMessages(
                "What should we do?", null, drafts, List.of(), List.of(), List.of(), true, budget));

        assertTrue(budgeted.length() < unbudgeted.length(), "budgeted prompt should be smaller");
        assertTrue(budget.truncated(), "truncation should be reported");
        assertTrue(budgeted.length() <= budget.totalChars(),
                   "budgeted prompt must fit the window it was built for");
    }

    @Test
    void instructionsAndQuestionSurviveTruncation() {
        List<Draft> drafts = List.of(draft("a"), draft("b"));
        PromptBudget budget = PromptBudget.forModel(chair(4096, 1800));

        List<com.debopam.llmcouncil.model.ChatMessage> messages = promptBuilder.synthesisMessages(
                "Should we migrate to Postgres?", null, drafts, List.of(), List.of(), List.of(), true, budget);

        // Truncating the task itself would be self-defeating: the model would no
        // longer know what it was asked or what format to answer in.
        assertTrue(messages.getFirst().content().contains("chair of an LLM council"));
        assertTrue(userContent(messages).contains("Should we migrate to Postgres?"));
    }

    @Test
    void unbudgetedOverloadStillBuildsTheFullPrompt() {
        // The no-budget overloads remain in use by tests and by any caller that
        // has no model in hand; they must not start truncating silently.
        List<Draft> drafts = List.of(draft("a"), draft("b"));

        String content = userContent(promptBuilder.synthesisMessages(
                "q", null, drafts, List.of(), List.of(), List.of(), true));

        assertTrue(content.contains("draft-a"));
        assertTrue(content.contains("draft-b"));
        assertEquals(-1, content.indexOf("truncated:"));
    }

    @Test
    void reviewPromptIsBudgetedToo() {
        // Every reviewer receives every draft, so review overflows on a small
        // window even when a single draft would fit comfortably.
        List<Draft> drafts = List.of(draft("a"), draft("b"), draft("c"), draft("d"));
        PromptBudget budget = PromptBudget.forModel(chair(4096, 1200));

        String content = userContent(promptBuilder.reviewMessages("q", drafts, budget));

        assertTrue(budget.truncated());
        assertTrue(content.length() <= budget.totalChars());
    }

    private String userContent(List<com.debopam.llmcouncil.model.ChatMessage> messages) {
        return messages.stream()
                       .map(com.debopam.llmcouncil.model.ChatMessage::content)
                       .reduce((first, second) -> second)
                       .orElse("");
    }

    private Draft draft(String suffix) {
        // ~1200 output tokens of text, matching the shipped local model config.
        return new Draft("draft-" + suffix, "model-" + suffix,
                         ("Position " + suffix + ". " + "reasoning ".repeat(500)));
    }

    private ModelProfile chair(int contextWindowTokens, int outputTokens) {
        return new ModelProfile("local-chair", "ollama", "llama3.1:8b", outputTokens, 0.2,
                                Duration.ofSeconds(240), ModelRole.CHAIR,
                                CouncilRole.SYNTHESIZER, "llama", contextWindowTokens);
    }
}
