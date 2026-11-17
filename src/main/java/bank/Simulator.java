package bank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Runs random transfers on many threads, watches for deadlock, and checks the books afterwards. */
public final class Simulator {

    public record Result(String bank, int threads, int accounts, long attempted, long completed, long rejected,
                         double seconds, List<String> deadlock, long totalBefore, long totalAfter,
                         boolean ledgerMatches, long retries) {
        public boolean deadlocked() { return !deadlock.isEmpty(); }
        public boolean moneyConserved() { return totalBefore == totalAfter; }
        public double transfersPerSecond() { return completed / seconds; }
    }

    /**
     * @param transfersPerThread transfers each worker attempts
     * @param seed               base seed; worker i uses seed + i, so a run is repeatable per thread
     * @param deadlockCheckMs    how often the watchdog looks for a deadlock
     */
    public static Result run(AbstractBank bank, int threads, int transfersPerThread, long seed,
                             long deadlockCheckMs) throws InterruptedException {
        if (threads < 1 || transfersPerThread < 0) throw new IllegalArgumentException("bad workload");
        long[] opening = bank.balances();
        long totalBefore = bank.total();
        AtomicLong attempted = new AtomicLong(), completed = new AtomicLong(), rejected = new AtomicLong();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            final long s = seed + w;
            Thread t = new Thread(() -> {
                Random rnd = new Random(s);
                int n = bank.size();
                try {
                    for (int i = 0; i < transfersPerThread; i++) {
                        int from = rnd.nextInt(n);
                        int to = rnd.nextInt(n - 1);
                        if (to >= from) to++;                        // any account except `from`
                        long amount = 1 + rnd.nextInt(200);
                        attempted.incrementAndGet();
                        if (bank.transfer(from, to, amount)) completed.incrementAndGet();
                        else rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();              // stopped by the watchdog
                }
            }, "worker-" + w);
            workers.add(t);
        }
        Set<Long> ids = new HashSet<>();
        long start = System.nanoTime();
        for (Thread t : workers) {
            t.start();
            ids.add(t.threadId());
        }
        List<String> deadlock = List.of();
        for (Thread t : workers) {
            while (t.isAlive()) {
                t.join(deadlockCheckMs);
                if (t.isAlive()) {
                    List<String> found = DeadlockDetector.find(ids);
                    if (!found.isEmpty()) {
                        deadlock = found;
                        for (Thread w : workers) w.interrupt();     // locks are interruptible, so this recovers
                    }
                }
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        long retries = bank instanceof Banks.TryLocks tl ? tl.retries.get() : 0;
        boolean ledgerOk = Arrays.equals(bank.ledger().replay(opening), bank.balances());
        return new Result(bank.name(), threads, bank.size(), attempted.get(), completed.get(), rejected.get(),
                seconds, deadlock, totalBefore, bank.total(), ledgerOk, retries);
    }
}
