package com.github.grepHammerspace.stateStore;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginSessionsTest {
    private static final String USER = "user-1";

    private final LoginSessions sessions = new LoginSessions();

    private static LoginSession session(Future<?> login, Instant startedAt) {
        return new LoginSession(LoginFlow.AZURE_PUSH, null, login, startedAt);
    }

    @Test
    void returnsTheSessionPutForAUser() {
        LoginSession session = session(CompletableFuture.completedFuture(null), Instant.now());
        sessions.put(USER, session);
        assertSame(session, sessions.get(USER));
    }

    @Test
    void anExpiredSessionIsGone() {
        sessions.put(USER, session(CompletableFuture.completedFuture(null),
                Instant.now().minus(LoginSession.TTL).minusSeconds(1)));
        assertNull(sessions.get(USER));
    }

    // The regression this pins: CompletableFuture.cancel(true) never interrupted the poll thread,
    // so a replaced Azure session kept polling Microsoft with the old driver for up to two minutes.
    @Test
    void replacingASessionInterruptsItsBackgroundPoll() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> poll = pool.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
            });
            sessions.put(USER, session(poll, Instant.now()));
            started.await();

            sessions.put(USER, session(CompletableFuture.completedFuture(null), Instant.now()));

            assertTrue(poll.isCancelled());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the old poll was not interrupted");
        }
    }

    @Test
    void removeLeavesASessionThatReplacedIt() {
        LoginSession spent = session(CompletableFuture.completedFuture(null), Instant.now());
        LoginSession newer = session(CompletableFuture.completedFuture(null), Instant.now());
        sessions.put(USER, spent);
        sessions.put(USER, newer);

        sessions.remove(USER, spent);

        assertSame(newer, sessions.get(USER));
    }
}
