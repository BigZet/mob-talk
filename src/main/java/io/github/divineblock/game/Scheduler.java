package io.github.divineblock.game;

import java.util.ArrayList;
import java.util.List;

/** Delayed and repeating work on the server thread, counted in server ticks; cleared when the server stops. */
public final class Scheduler {
    private record Task(long due, Runnable action) { }
    private static final List<Task> TASKS = new ArrayList<>();
    private static long tick;

    private Scheduler() { }

    public static synchronized void later(int ticks, Runnable action) { TASKS.add(new Task(tick + Math.max(1, ticks), action)); }

    /** Runs {@code step} {@code times} times, every {@code every} ticks, starting after the first interval. */
    public static void repeat(int every, int times, java.util.function.IntConsumer step) {
        for (int i = 0; i < times; i++) { int index = i; later(every * (i + 1), () -> step.accept(index)); }
    }

    static void tick() {
        List<Task> due = new ArrayList<>();
        synchronized (Scheduler.class) {
            tick++;
            TASKS.removeIf(task -> { if (task.due() <= tick) { due.add(task); return true; } return false; });
        }
        for (Task task : due) {
            try { task.action().run(); }
            catch (RuntimeException failure) { io.github.divineblock.DivineBlockMod.LOG.warn("Божественный блок: отложенное действие упало", failure); }
        }
    }

    static synchronized void clear() { TASKS.clear(); }
}
