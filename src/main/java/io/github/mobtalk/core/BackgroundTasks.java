package io.github.mobtalk.core;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The mod's one background lane for cheap model requests that no reply waits for: conversation summaries and reflections.
 * One daemon thread, so at most one such request is in flight across every world and mob; a job already queued under the
 * same key is not queued twice; a failed job only leaves a diagnostic line. Nothing here ever runs on the game thread. */
final class BackgroundTasks {
    private BackgroundTasks() { }

    interface Job { void run() throws Exception; }

    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-background"); thread.setDaemon(true); return thread;
    });
    private static final Set<String> PENDING = new HashSet<>();

    /** Queues the job unless one with this key is already waiting or running; returns whether it was queued. */
    static boolean submit(String key, Job job) {
        synchronized (PENDING) { if (!PENDING.add(key)) return false; }
        try {
            QUEUE.execute(() -> {
                try { job.run(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (Exception | LinkageError failure) {
                    OpenRouterConversationClient.diagnostics.accept("фоновая задача " + key + " не удалась: " + failure.getClass().getSimpleName()
                            + (failure.getMessage() == null ? "" : ": " + TextBounds.characters(failure.getMessage(), 200)));
                } finally {
                    Thread.interrupted();
                    synchronized (PENDING) { PENDING.remove(key); }
                }
            });
            return true;
        } catch (RuntimeException rejected) { synchronized (PENDING) { PENDING.remove(key); } return false; }
    }

    /** Whether a job with this key is waiting or running. */
    static boolean pending(String key) { synchronized (PENDING) { return PENDING.contains(key); } }

    /** Waits until every job queued so far has finished; for tests and shutdown, never the game thread. */
    static void awaitIdle(long millis) throws InterruptedException, TimeoutException {
        Future<?> barrier = QUEUE.submit(() -> { });
        try { barrier.get(millis, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.ExecutionException impossible) { /* An empty task cannot fail. */ }
    }
}
