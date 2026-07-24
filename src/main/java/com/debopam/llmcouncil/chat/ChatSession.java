package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.domain.DepthMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Process-local chat aggregate used by the demo Chat API.
 *
 * <p>A chat is intentionally separate from a {@code CouncilSession}. One chat
 * can create many council sessions, one per user turn. Public methods are
 * synchronized because async run completion updates turns from virtual threads.
 */
public class ChatSession {
    private final String id;
    private final String profileId;
    private final DepthMode depthMode;
    private final Instant createdAt;
    private Instant updatedAt;
    private String summary;
    private final List<ChatTurn> turns = new ArrayList<>();

    public ChatSession(String id, String profileId, DepthMode depthMode, String summary) {
        // One instant for both, so a brand-new chat's createdAt and updatedAt
        // are equal rather than nanoseconds apart.
        Instant now = Instant.now();
        this(id, profileId, depthMode, summary, now, now);
    }

    /**
     * Full constructor, used when rebuilding a stored chat.
     *
     * <p>Private because everything except {@link #fromSnapshot} should be
     * creating a <em>new</em> chat, which stamps its own timestamps. Restoring
     * one must not: a chat whose {@code createdAt} were reset on every load
     * would never age past the retention bound, and would climb back to the top
     * of a list sorted by recency the moment it was read.
     *
     * @param id        the chat id
     * @param profileId the council profile every turn runs under
     * @param depthMode the depth mode every turn runs at
     * @param summary   the rolling conversation summary, null treated as empty
     * @param createdAt when the chat was first created
     * @param updatedAt when the chat last changed
     */
    private ChatSession(String id, String profileId, DepthMode depthMode, String summary,
                        Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.profileId = profileId;
        this.depthMode = depthMode;
        this.summary = summary == null ? "" : summary;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Capture this chat's state for storage.
     *
     * <p>Synchronized with every mutator, so the snapshot is one consistent
     * moment rather than a turn list read while a completing run was writing
     * into it.
     *
     * @return an immutable snapshot of the whole chat
     */
    public synchronized ChatSessionSnapshot toSnapshot() {
        return new ChatSessionSnapshot(id, profileId, depthMode, summary,
                                       List.copyOf(turns), createdAt, updatedAt);
    }

    /**
     * Rebuild a chat from storage.
     *
     * @param snapshot the stored state
     * @return the chat, with its original timestamps and turn order intact
     */
    public static ChatSession fromSnapshot(ChatSessionSnapshot snapshot) {
        ChatSession chat = new ChatSession(snapshot.id(), snapshot.profileId(),
                                           snapshot.depthMode(), snapshot.summary(),
                                           snapshot.createdAt(), snapshot.updatedAt());
        chat.turns.addAll(snapshot.turns());
        return chat;
    }

    public String id() { return id; }
    public String profileId() { return profileId; }
    public DepthMode depthMode() { return depthMode; }
    public Instant createdAt() { return createdAt; }
    public synchronized Instant updatedAt() { return updatedAt; }
    public synchronized String summary() { return summary; }
    public synchronized List<ChatTurn> turns() { return List.copyOf(turns); }

    public synchronized void addTurn(ChatTurn turn) {
        turns.add(turn);
        updatedAt = Instant.now();
    }

    public synchronized Optional<ChatTurn> turn(String turnId) {
        return turns.stream().filter(turn -> turn.id().equals(turnId)).findFirst();
    }

    public synchronized void replaceTurn(ChatTurn updatedTurn) {
        for (int i = 0; i < turns.size(); i++) {
            if (turns.get(i).id().equals(updatedTurn.id())) {
                turns.set(i, updatedTurn);
                updatedAt = Instant.now();
                return;
            }
        }
    }

    public synchronized void replaceSummary(String newSummary) {
        this.summary = newSummary == null ? "" : newSummary;
        updatedAt = Instant.now();
    }

    public synchronized List<ChatTurn> recentTurns(int count) {
        int from = Math.max(0, turns.size() - count);
        return List.copyOf(turns.subList(from, turns.size()));
    }

    public synchronized boolean hasRunningTurn() {
        return turns.stream().anyMatch(turn -> turn.status() == ChatTurnStatus.RUNNING);
    }
}
