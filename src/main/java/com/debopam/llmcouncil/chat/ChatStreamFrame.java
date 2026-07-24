package com.debopam.llmcouncil.chat;

/**
 * One frame of a chat's SSE stream, ready to write.
 *
 * <p>The stream carries two kinds of event under two different SSE event names,
 * and a resuming client needs them back in one ordered run rather than two. This
 * is what lets the controller emit them without knowing which store each came
 * from.
 *
 * @param name    the SSE event name the client listens on: {@code chat} or
 *                {@code council}
 * @param chatSeq the frame's position in the chat's sequence, which is what the
 *                client echoes back as its cursor
 * @param data    the event itself, serialised as the frame's JSON body
 */
public record ChatStreamFrame(String name, long chatSeq, Object data) {

    /** SSE event name for the chat's own events. */
    public static final String CHAT = "chat";

    /** SSE event name for the council events of the chat's turns. */
    public static final String COUNCIL = "council";
}
