package com.debopam.llmcouncil.persistence;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link SessionStore} must do, whatever it stores sessions in.
 *
 * <p>One contract, one subclass per implementation. This is the pattern that
 * keeps the in-memory and JDBC stores behaviourally identical: a durable store
 * that quietly differed — dropping a null field, resetting {@code createdAt} on
 * update, returning a stale copy after a save — would pass its own tests and
 * change how the application behaves only for users who had opted into
 * durability, which is the population least able to get a diagnosis.
 *
 * <p>Subclasses supply a store; everything else is inherited.
 *
 * <p>Every fixture here is timestamped inside the retention window on purpose.
 * The in-memory store evicts on write and the durable one is swept on a
 * schedule, so a fixture dated last year would be gone before the assertion ran
 * on one implementation and present on the other — and the test would be
 * reporting on retention rather than on the save/find contract it is named for.
 */
public abstract class SessionStoreContractTest {

    /**
     * @return a fresh, empty store of the implementation under test
     */
    protected abstract SessionStore createStore();

    @Test
    void aSavedSessionComesBackFieldForField() {
        SessionStore store = createStore();
        // Sub-second apart, so a store that truncated to whole seconds would
        // hand back a session whose updatedAt equalled its createdAt.
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(300);
        CouncilSession session = new CouncilSession(
                "s1", "Why is the sky blue?", "prior context", DepthMode.RIGOROUS, "multi-cloud",
                "policy-1", "protocol-1", CouncilStatus.COMPLETED,
                createdAt, createdAt.plusMillis(250),
                "Rayleigh scattering.", null);

        store.save(session);

        assertEquals(Optional.of(session), store.findById("s1"));
    }

    @Test
    void anUnknownIdIsEmptyRatherThanAnError() {
        assertTrue(createStore().findById("never-existed").isEmpty());
    }

    @Test
    void savingAgainReplacesRatherThanDuplicates() {
        // A run saves its session three times — created, running, terminal. A
        // store that inserted each time would return whichever row it happened
        // to read, so a finished run could report itself as still RUNNING.
        SessionStore store = createStore();
        CouncilSession created =
                CouncilSession.create("s2", "Why?", null, DepthMode.QUICK, "mock");
        store.save(created);

        store.save(created.withStatus(CouncilStatus.RUNNING));
        store.save(created.withStatus(CouncilStatus.COMPLETED).withFinalAnswer("done"));

        CouncilSession found = store.findById("s2").orElseThrow();
        assertEquals(CouncilStatus.COMPLETED, found.status());
        assertEquals("done", found.finalAnswer());
    }

    @Test
    void createdAtIsNotResetByALaterSave() {
        // The session's own age is what the retention sweep and the UI's "asked
        // 3 days ago" both read. A store that stamped createdAt on write would
        // make every session look new and nothing would ever age out.
        SessionStore store = createStore();
        CouncilSession created =
                CouncilSession.create("s3", "Why?", null, DepthMode.QUICK, "mock");
        Instant originalCreatedAt = created.createdAt();

        store.save(created);
        store.save(created.withStatus(CouncilStatus.COMPLETED));

        assertEquals(originalCreatedAt, store.findById("s3").orElseThrow().createdAt());
    }

    @Test
    void nullFieldsStayNull() {
        // "" and null differ in the UI: an empty failure reason renders as a
        // failure that happened, with nothing to say about it.
        SessionStore store = createStore();
        CouncilSession session =
                CouncilSession.create("s4", "Why?", null, DepthMode.QUICK, "mock");

        store.save(session);

        CouncilSession found = store.findById("s4").orElseThrow();
        assertNull(found.context());
        assertNull(found.finalAnswer());
        assertNull(found.failureReason());
        assertNull(found.policyId());
    }

    @Test
    void storesAreIndependentOfEachOther() {
        // Positive control for every "not found" assertion here: a store the
        // factory had accidentally made shared would let earlier tests' rows
        // leak into later ones, and several assertions above would pass by
        // coincidence.
        SessionStore first = createStore();
        first.save(CouncilSession.create("s5", "Why?", null, DepthMode.QUICK, "mock"));

        assertTrue(createStore().findById("s5").isEmpty(),
                   "each createStore() call must hand back an empty store");
        assertTrue(first.findById("s5").isPresent());
    }

    @Test
    void aLongAnswerIsStoredWhole() {
        // Council answers run to thousands of characters, and a column that
        // truncated would do so on real output and no fixture.
        SessionStore store = createStore();
        String answer = "answer ".repeat(20_000);
        store.save(CouncilSession.create("s6", "Why?", null, DepthMode.QUICK, "mock")
                                 .withFinalAnswer(answer));

        assertEquals(answer, store.findById("s6").orElseThrow().finalAnswer());
    }
}
