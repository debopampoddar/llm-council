package com.debopam.llmcouncil.api;

import com.debopam.llmcouncil.chat.ChatCouncilService;
import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatSessionStore;
import com.debopam.llmcouncil.chat.ChatTurn;
import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ChatListingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatCouncilService chatService;

    @Autowired
    private ChatSessionStore chatStore;

    @Test
    void listsChatsWithoutTurnBodies() throws Exception {
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);
        chat.addTurn(ChatTurn.running(UUID.randomUUID().toString(), "what is a council?", "session-x")
                             .completed("a very long answer that should not appear in the listing"));
        chatStore.save(chat);

        mockMvc.perform(get("/api/council/chats"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[?(@.chatId == '" + chat.id() + "')].firstUserMessage")
                                  .value("what is a council?"))
               .andExpect(jsonPath("$[?(@.chatId == '" + chat.id() + "')].turnCount").value(1))
               .andExpect(jsonPath("$[0].turns").doesNotExist());
    }

    @Test
    void deletesAnIdleChat() throws Exception {
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);

        mockMvc.perform(delete("/api/council/chats/" + chat.id()))
               .andExpect(status().isNoContent());

        assertTrue(chatStore.findById(chat.id()).isEmpty());
    }

    @Test
    void refusesToDeleteAChatWithARunningTurn() throws Exception {
        // A run in flight would complete and write back to a chat that no longer
        // exists, so deletion has to wait rather than race.
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);
        chat.addTurn(ChatTurn.running(UUID.randomUUID().toString(), "still thinking", "session-y"));
        chatStore.save(chat);

        mockMvc.perform(delete("/api/council/chats/" + chat.id()))
               .andExpect(status().isConflict());

        assertTrue(chatStore.findById(chat.id()).isPresent());
    }

    @Test
    void deletingAnUnknownChatIsNotFound() throws Exception {
        mockMvc.perform(delete("/api/council/chats/does-not-exist"))
               .andExpect(status().isNotFound());
    }
}
