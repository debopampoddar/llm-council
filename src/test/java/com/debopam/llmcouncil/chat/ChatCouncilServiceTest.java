package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "council.runtime.max-concurrent-runs=1",
        "council.runtime.chat-recent-turn-count=2",
        "council.persistence.artifact-base-path=/private/tmp/llm-council-chat-test"
})
class ChatCouncilServiceTest {

    @Autowired
    private ChatCouncilService chatService;

    @Test
    void askCreatesCouncilSessionAndCompletesTurnWithMockProfile() throws Exception {
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, "Demo context");

        chatService.ask(chat.id(), "Explain why a chat layer helps the council demo.");

        ChatSession completed = waitForIdle(chat.id(), Duration.ofSeconds(5));
        assertFalse(completed.hasRunningTurn());
        assertEquals(1, completed.turns().size());

        ChatTurn turn = completed.turns().getFirst();
        assertEquals(ChatTurnStatus.COMPLETED, turn.status());
        assertNotNull(turn.councilSessionId());
        assertTrue(turn.assistantAnswer().contains("Mock final answer"));
        assertTrue(completed.summary().contains("Recent decisions"));
    }

    private ChatSession waitForIdle(String chatId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        ChatSession chat = chatService.getChat(chatId);
        while (System.nanoTime() < deadline) {
            chat = chatService.getChat(chatId);
            if (!chat.hasRunningTurn()) {
                return chat;
            }
            Thread.sleep(50);
        }
        return chat;
    }
}
