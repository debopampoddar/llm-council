package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CouncilRunExecutorTest {

    @Test
    void rejectsSecondRunWhenConcurrencyLimitIsReached() throws Exception {
        BlockingCouncilService councilService = new BlockingCouncilService();
        CouncilRunExecutor executor = new CouncilRunExecutor(councilService, 1);
        try {
            CouncilRunSubmission first = executor.submit("session-1", ignored -> {});
            assertTrue(first.accepted());
            assertTrue(councilService.started.await(2, TimeUnit.SECONDS));

            CouncilRunSubmission second = executor.submit("session-2", ignored -> {});

            assertFalse(second.accepted());
        } finally {
            councilService.release.countDown();
            executor.shutdown();
        }
    }

    private static class BlockingCouncilService extends CouncilService {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingCouncilService() {
            super(null, null, null, new RunRegistry());
        }

        @Override
        public CouncilContext runCouncil(String sessionId) {
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return null;
        }

        @Override
        public CouncilSession getSession(String sessionId) {
            return new CouncilSession(
                    sessionId,
                    "question",
                    null,
                    DepthMode.QUICK,
                    "mock",
                    "mock-quick",
                    "quick",
                    CouncilStatus.COMPLETED,
                    java.time.Instant.now(),
                    java.time.Instant.now(),
                    "answer",
                    null);
        }
    }
}
