package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.application.ChatAttribution;
import com.debopam.llmcouncil.application.ChatPosition;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which chat a running council session belongs to.
 *
 * <p>Council events are published with a session id and nothing else, but the
 * chat stream needs them numbered in the chat's sequence. Something has to hold
 * the association, and it cannot be a lookup through the session store: that
 * would be a query per event on the hot path of every stage.
 *
 * <p>Process-local, and correct as such. The link is only needed while the run
 * is executing, and no run survives a restart — an interrupted one is marked
 * interrupted, never resumed — so the map's useful life is exactly the run's.
 * The stored {@code chat_seq} on each event is what survives; this does not need
 * to.
 *
 * <p>Bounded even so. Links are removed when a turn finishes, but a bounded map
 * means a missed removal costs one wrong attribution rather than an
 * ever-growing map — and unbounded growth is the defect the retention work
 * removed from every other structure in this process.
 */
@Component
public class ChatTurnAttribution implements ChatAttribution {

    /**
     * How many session-to-chat links to keep.
     *
     * <p>Far above {@code council.runtime.max-concurrent-runs}, which defaults
     * to 1, so eviction should never touch a live run.
     */
    private static final int MAX_LINKS = 1_000;

    private final Map<String, String> chatBySession = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_LINKS;
                }
            });

    private final ChatSequenceAllocator sequences;

    /**
     * @param sequences allocates positions in a chat's sequence
     */
    public ChatTurnAttribution(ChatSequenceAllocator sequences) {
        this.sequences = sequences;
    }

    /**
     * Record that a council session is running on behalf of a chat turn.
     *
     * @param councilSessionId the council session the turn created
     * @param chatId           the chat that owns it
     */
    public void link(String councilSessionId, String chatId) {
        chatBySession.put(councilSessionId, chatId);
    }

    /**
     * Forget a finished turn's session.
     *
     * @param councilSessionId the council session that has finished
     */
    public void unlink(String councilSessionId) {
        chatBySession.remove(councilSessionId);
    }

    @Override
    public Optional<ChatPosition> nextPositionFor(String councilSessionId) {
        String chatId = chatBySession.get(councilSessionId);
        if (chatId == null) {
            return Optional.empty();
        }
        return Optional.of(new ChatPosition(chatId, sequences.next(chatId)));
    }
}
