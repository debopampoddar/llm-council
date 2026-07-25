package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.application.EventStore;
import com.debopam.llmcouncil.application.RunRegistry;
import com.debopam.llmcouncil.chat.ChatEventBroker;
import com.debopam.llmcouncil.chat.ChatSessionStore;
import com.debopam.llmcouncil.config.CouncilCatalogHolder;
import com.debopam.llmcouncil.config.RetentionPolicy;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Sweeps the durable stores back inside their retention bounds.
 *
 * <p>Durability moved unbounded growth from RAM to disk; it did not remove it.
 * The in-memory stores evict on write, which is right for them — the sweep costs
 * microseconds beside the model call that produced the entry, and the bound then
 * holds continuously. Deleting database rows and whole artifact directories does
 * not belong on that path, so it happens here instead, on a timer.
 *
 * <p>The bounds and the two rules that matter come from {@link RetentionPolicy},
 * the same one the in-memory stores use: oldest first, and never evict something
 * still in use. Both are easy to get subtly wrong in a way nothing notices —
 * evicting newest-first still bounds the store, and evicting a live run's
 * history still passes a size assertion.
 *
 * <p><b>The cascade is the reason this is more than a delete.</b> A session is
 * three things on disk: a row, its events, and an artifact directory that is by
 * far the largest of them. Removing the row alone would leave the other two
 * unreachable forever, since every read of them is scoped to a session that no
 * longer exists — the store would look bounded and the disk would keep filling.
 */
@Component
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final SessionStore sessions;
    private final EventStore events;
    private final ArtifactStore artifacts;
    private final ChatSessionStore chats;
    private final ChatEventBroker chatEvents;
    private final RunRegistry runs;
    private final RetentionPolicy retention;

    /**
     * @param sessions      the session store to sweep
     * @param events        council events, cascaded from the session
     * @param artifacts     artifact directories, cascaded from the session
     * @param chats         the chat store to sweep
     * @param chatEvents    chat events and sequence counters, cascaded from the chat
     * @param runs          in-flight runs, which are never evicted
     * @param catalogHolder supplies the resolved bounds, so a user overlay's
     *                      {@code retention} section takes effect
     */
    @Autowired
    public RetentionService(SessionStore sessions,
                            EventStore events,
                            ArtifactStore artifacts,
                            ChatSessionStore chats,
                            ChatEventBroker chatEvents,
                            RunRegistry runs,
                            CouncilCatalogHolder catalogHolder) {
        this(sessions, events, artifacts, chats, chatEvents, runs,
             new RetentionPolicy(catalogHolder.get().runtime().retention()));
    }

    /**
     * Direct construction with explicit bounds, for tests.
     *
     * @param sessions   the session store to sweep
     * @param events     council events, cascaded from the session
     * @param artifacts  artifact directories, cascaded from the session
     * @param chats      the chat store to sweep
     * @param chatEvents chat events and sequence counters, cascaded from the chat
     * @param runs       in-flight runs, which are never evicted
     * @param retention  the bounds to enforce
     */
    public RetentionService(SessionStore sessions,
                            EventStore events,
                            ArtifactStore artifacts,
                            ChatSessionStore chats,
                            ChatEventBroker chatEvents,
                            RunRegistry runs,
                            RetentionPolicy retention) {
        this.sessions = sessions;
        this.events = events;
        this.artifacts = artifacts;
        this.chats = chats;
        this.chatEvents = chatEvents;
        this.runs = runs;
        this.retention = retention;
    }

    /**
     * Run the sweep on a timer.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}, so a slow sweep on a
     * large store does not overlap itself. The first run is one interval after
     * startup rather than at startup: a sweep is destructive, and running it
     * before the application has finished coming up would delete on the strength
     * of whatever state happened to be loaded.
     */
    @Scheduled(fixedDelayString = "${council.persistence.retention.sweep-interval-ms:3600000}",
               initialDelayString = "${council.persistence.retention.sweep-interval-ms:3600000}")
    public void scheduledSweep() {
        sweep();
    }

    /**
     * Bring both stores back inside their bounds, cascading as it goes.
     *
     * <p>Public and separate from the schedule so it can be exercised directly,
     * with no timer and no timing-dependent assertion.
     *
     * @return how many sessions and chats were removed
     */
    public int sweep() {
        return sweepSessions() + sweepChats();
    }

    /**
     * Evict sessions past the size or age bound, oldest first.
     *
     * @return how many sessions were removed
     */
    private int sweepSessions() {
        List<RetentionPolicy.Candidate<String>> candidates = sessions.findAll().stream()
                .map(session -> new RetentionPolicy.Candidate<>(
                        session.id(), session.updatedAt(), inUse(session)))
                .toList();

        List<String> evictions = retention.selectEvictions(candidates, Instant.now());
        for (String sessionId : evictions) {
            // Events and artifacts before the session itself. A failure part way
            // through then leaves a session whose remains are still reachable
            // and will be swept again, rather than orphaning them permanently.
            events.deleteSession(sessionId);
            artifacts.deleteSession(sessionId);
            sessions.delete(sessionId);
            log.debug("Evicted session {} and its events and artifacts", sessionId);
        }
        if (!evictions.isEmpty()) {
            log.info("Retention removed {} session(s) past the configured bounds", evictions.size());
        }
        return evictions.size();
    }

    /**
     * Evict chats past the size or age bound, oldest first.
     *
     * @return how many chats were removed
     */
    private int sweepChats() {
        List<RetentionPolicy.Candidate<String>> candidates = chats.findAll().stream()
                .map(chat -> new RetentionPolicy.Candidate<>(
                        chat.id(), chat.updatedAt(), chat.hasRunningTurn()))
                .toList();

        List<String> evictions = retention.selectEvictions(candidates, Instant.now());
        for (String chatId : evictions) {
            chatEvents.forgetChat(chatId);
            chats.delete(chatId);
            log.debug("Evicted chat {} and its events", chatId);
        }
        if (!evictions.isEmpty()) {
            log.info("Retention removed {} chat(s) past the configured bounds", evictions.size());
        }
        return evictions.size();
    }

    /**
     * Whether a session's work is still outstanding.
     *
     * <p>Three ways of asking the same question, because each catches a case the
     * others miss. {@code CREATED} is a session awaiting its run and
     * {@code RUNNING} one in progress; the registry catches a run whose stored
     * status has not caught up with reality, which is exactly the window in
     * which deleting it would be worst.
     *
     * @param session the session to classify
     * @return {@code true} when it must not be evicted whatever its age
     */
    private boolean inUse(CouncilSession session) {
        return session.status() == CouncilStatus.RUNNING
               || session.status() == CouncilStatus.CREATED
               || runs.find(session.id()).isPresent();
    }
}
