package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.application.EventStore;
import com.debopam.llmcouncil.domain.CouncilEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Replays a chat's whole stream from a cursor position.
 *
 * <p>This is what the shared per-chat sequence was for. The stream multiplexes
 * the chat's own events and the council events of each of its turns, held in
 * two different stores; because both draw their positions from one allocator,
 * merging them is a sort rather than a reconciliation, and the cursor a client
 * echoes back is a single integer that means the same thing to both.
 *
 * <p>Used only when a client actually presents a cursor. A first connection
 * still replays full history through the existing path, which is lossless in a
 * way this is not: an event whose position could not be allocated — the
 * allocator failed, and the failure was logged rather than allowed to kill the
 * turn — has no place in the sequence and cannot appear in a merged replay.
 * Resuming past one is the correct trade; starting from nothing and silently
 * dropping it is not.
 */
@Component
public class ChatStreamReplay {

    private final ChatEventStore chatEvents;
    private final EventStore councilEvents;

    /**
     * @param chatEvents    the chat's own event log
     * @param councilEvents the council event log its turns write into
     */
    public ChatStreamReplay(ChatEventStore chatEvents, EventStore councilEvents) {
        this.chatEvents = chatEvents;
        this.councilEvents = councilEvents;
    }

    /**
     * Everything in one chat's stream after a position, in position order.
     *
     * @param chatId  the chat to replay
     * @param chatSeq the last position the client already has
     * @return frames after that position, oldest first
     */
    public List<ChatStreamFrame> since(String chatId, long chatSeq) {
        List<ChatStreamFrame> frames = new ArrayList<>();
        for (ChatEvent event : chatEvents.since(chatId, chatSeq)) {
            frames.add(new ChatStreamFrame(ChatStreamFrame.CHAT, event.chatSeq(), event));
        }
        for (CouncilEvent event : councilEvents.sinceInChat(chatId, chatSeq)) {
            frames.add(new ChatStreamFrame(ChatStreamFrame.COUNCIL, event.chatSeq(), event));
        }
        frames.sort(Comparator.comparingLong(ChatStreamFrame::chatSeq));
        return List.copyOf(frames);
    }
}
