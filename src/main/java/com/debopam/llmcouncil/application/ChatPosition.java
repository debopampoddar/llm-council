package com.debopam.llmcouncil.application;

/**
 * A council event's place in the chat that owns it.
 *
 * @param chatId  the chat whose stream this event belongs to
 * @param chatSeq its position in that chat's sequence, counting from 1
 */
public record ChatPosition(String chatId, long chatSeq) {
}
