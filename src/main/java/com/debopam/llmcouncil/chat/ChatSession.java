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
        this.id = id;
        this.profileId = profileId;
        this.depthMode = depthMode;
        this.summary = summary == null ? "" : summary;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
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
