package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the two halves of {@link EventPublisher} fit back together.
 *
 * <p>Splitting store from broker introduced exactly one new way to be wrong:
 * delivering an event before storing it. A subscriber that reacts to a frame by
 * asking for history — which is what the UI does — would then be told the event
 * it had just been handed does not exist. Nothing else in the suite would
 * notice, because the two calls are microseconds apart in a single-threaded
 * test and the order only matters to a caller that acts on the first.
 */
class EventPublisherCompositionTest {

    @Test
    void anEventIsInHistoryBeforeAnySubscriberHearsAboutIt() {
        InMemoryEventStore store = new InMemoryEventStore();
        EventPublisher publisher = new DefaultEventPublisher(store, new InMemoryEventBroker());
        List<Boolean> historyHadItAtDeliveryTime = new ArrayList<>();
        publisher.subscribe("s1", event ->
                historyHadItAtDeliveryTime.add(store.history("s1").contains(event)));

        publisher.publish("s1", "GENERATE", "DRAFT_COMPLETED", "model-a", Map.of());

        assertEquals(List.of(true), historyHadItAtDeliveryTime,
                     "a client that reacts to a frame by fetching history must not be told the "
                     + "event it just received does not exist");
    }

    @Test
    void subscribersOnlyHearAboutTheirOwnSession() {
        EventPublisher publisher = new DefaultEventPublisher();
        List<CouncilEvent> heard = new ArrayList<>();
        publisher.subscribe("mine", heard::add);

        publisher.publish("someone-else", "GENERATE", "DRAFT_COMPLETED", null, Map.of());
        publisher.publish("mine", "GENERATE", "DRAFT_COMPLETED", null, Map.of());

        assertEquals(1, heard.size());
        assertEquals("mine", heard.getFirst().sessionId());
    }

    @Test
    void closingASubscriptionStopsDelivery() {
        // Dead SSE emitters accumulating for the life of the process is what
        // this handle exists to prevent.
        EventPublisher publisher = new DefaultEventPublisher();
        List<CouncilEvent> heard = new ArrayList<>();
        AutoCloseable subscription = publisher.subscribe("s1", heard::add);

        publisher.publish("s1", "GENERATE", "FIRST", null, Map.of());
        closeQuietly(subscription);
        publisher.publish("s1", "GENERATE", "SECOND", null, Map.of());

        assertEquals(List.of("FIRST"), heard.stream().map(CouncilEvent::type).toList());
    }

    @Test
    void historyStillReplaysThroughThePublisherFacade() {
        // Fourteen classes take an EventPublisher and none of them know about
        // the split. history() must keep working through the facade.
        EventPublisher publisher = new DefaultEventPublisher();

        publisher.publish("s1", "GENERATE", "FIRST", null, Map.of());
        publisher.publish("s1", "SYNTHESIZE", "SECOND", null, Map.of());

        assertEquals(List.of("FIRST", "SECOND"),
                     publisher.history("s1").stream().map(CouncilEvent::type).toList());
        assertTrue(publisher.history("unknown-session").isEmpty());
    }

    private void closeQuietly(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception ex) {
            throw new IllegalStateException("Subscription close should not throw", ex);
        }
    }
}
