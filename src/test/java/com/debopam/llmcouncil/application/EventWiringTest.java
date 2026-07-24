package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.chat.ChatEventBroker;
import com.debopam.llmcouncil.chat.ChatEventStore;
import com.debopam.llmcouncil.chat.ChatSession;
import com.debopam.llmcouncil.chat.ChatCouncilService;
import com.debopam.llmcouncil.domain.CouncilEvent;
import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the container's publisher and broker write into the stores everything
 * else reads.
 *
 * <p>Worth a test of its own because of how quietly it can be wrong. Both
 * classes offer a convenience no-argument constructor for tests, and Spring
 * picks the no-argument constructor when a class has several and none is
 * annotated. The container then holds a publisher wired to a private in-memory
 * store: every council event is written somewhere nothing else can read, the
 * event history endpoint still works — it reads through the same publisher —
 * and under {@code council.persistence.type=jdbc} nothing reaches the database
 * at all. Nothing logs, nothing throws, and the application looks healthy.
 *
 * <p>Every assertion here therefore publishes through one bean and reads through
 * a different one. A test that did both through the publisher would pass against
 * exactly the defect it was written for.
 */
@SpringBootTest
class EventWiringTest {

    @Autowired
    private EventPublisher publisher;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private ChatEventBroker chatEvents;

    @Autowired
    private ChatEventStore chatEventStore;

    @Autowired
    private ChatCouncilService chatService;

    @Test
    void theContainersPublisherWritesIntoTheContainersEventStore() {
        String sessionId = UUID.randomUUID().toString();

        publisher.publish(sessionId, "GENERATE", "DRAFT_COMPLETED", "mock-model", Map.of());

        assertEquals(1, eventStore.history(sessionId).size(),
                     "published through the publisher bean, read through the store bean");
        assertEquals("DRAFT_COMPLETED", eventStore.history(sessionId).getFirst().type());
    }

    @Test
    void theContainersBrokerWritesIntoTheContainersChatEventStore() {
        String chatId = UUID.randomUUID().toString();

        chatEvents.publish(chatId, "CHAT_CREATED", Map.of());

        assertEquals(1, chatEventStore.history(chatId).size(),
                     "published through the broker bean, read through the store bean");
    }

    @Test
    void aChatCreatedThroughTheServiceLeavesAReadableEventBehind() {
        // The path a user actually takes, rather than a direct publish.
        ChatSession chat = chatService.createChat("mock", DepthMode.QUICK, null);

        assertEquals("CHAT_CREATED", chatEventStore.history(chat.id()).getFirst().type());
        assertTrue(chatEventStore.history(chat.id()).getFirst().chatSeq() > 0,
                   "and it was allocated a position in the chat's sequence");
    }

    @Test
    void theStoreASubscriberSeesIsTheStoreTheHistoryComesFrom() {
        // The composition's own ordering rule, checked across beans: an event
        // delivered live must already be findable in the store a different
        // component would read.
        String sessionId = UUID.randomUUID().toString();
        CouncilEvent published =
                publisher.publish(sessionId, "SYNTHESIZE", "STAGE_COMPLETED", null, Map.of());

        assertTrue(eventStore.history(sessionId).contains(published));
    }
}
