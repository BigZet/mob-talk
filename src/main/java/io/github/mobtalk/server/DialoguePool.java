package io.github.mobtalk.server;

import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Пул диалога на сервере с приоритетами (бриф A): ответ собеседнику раньше реакции на подарок, та раньше очевидцев, те раньше
 * болтовни деревни. Несколько потоков, чтобы очевидцы и соседи думали параллельно с ответом; порядок внутри приоритета — по
 * поступлению. Задачи прерываемы ({@link Future#cancel(boolean)}), как раньше конвейер клиента. */
public final class DialoguePool {
    public enum Priority { ANSWER, REACTION, WITNESS, VILLAGE, MAINTENANCE }

    private static final class Job extends FutureTask<Void> implements Comparable<Job> {
        final Priority priority; final long seq;
        Job(Runnable work, Priority priority, long seq) { super(work, null); this.priority = priority; this.seq = seq; }
        @Override public int compareTo(Job other) {
            int byPriority = priority.compareTo(other.priority);
            return byPriority != 0 ? byPriority : Long.compare(seq, other.seq);
        }
    }

    private final AtomicLong sequence = new AtomicLong();
    private final ThreadPoolExecutor executor;

    public DialoguePool(int threads) {
        executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "mobtalk-dialogue"); thread.setDaemon(true); return thread;
        });
        executor.allowCoreThreadTimeOut(true);
    }

    public Future<?> submit(Priority priority, Runnable work) {
        Job job = new Job(work, priority, sequence.incrementAndGet());
        executor.execute(job);
        return job;
    }

    public void shutdown() { executor.shutdownNow(); }
}
