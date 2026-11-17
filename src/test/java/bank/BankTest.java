package bank;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class BankTest {
    static final String[] SAFE = {"global", "naive", "ordered", "trylock"};   // correct when not deadlocked
    static final String[] DEADLOCK_FREE = {"global", "ordered", "trylock"};

    // ---- single-threaded behaviour ---------------------------------------

    @Test
    public void transferMovesMoneyAndRecordsIt() throws Exception {
        for (String name : Banks.NAMES) {
            AbstractBank b = Banks.create(name, 3, 100);
            assertTrue(name, b.transfer(0, 2, 40));
            assertEquals(name, 60, b.balance(0));
            assertEquals(name, 140, b.balance(2));
            assertEquals(name, 300, b.total());
            assertEquals(name, List.of(new Ledger.Entry(0, 0, 2, 40)), b.ledger().entries());
        }
    }

    @Test
    public void insufficientFundsAreRejectedWithoutSideEffects() throws Exception {
        for (String name : Banks.NAMES) {
            AbstractBank b = Banks.create(name, 2, 50);
            assertFalse(name, b.transfer(0, 1, 51));
            assertTrue(name, b.transfer(0, 1, 50));          // exactly the balance is allowed
            assertEquals(name, 0, b.balance(0));
            assertEquals(name, 1, b.ledger().size());
        }
    }

    @Test
    public void invalidArgumentsAreRejected() throws Exception {
        AbstractBank b = Banks.create("ordered", 2, 10);
        for (int[] bad : new int[][] {{0, 0, 1}, {0, 2, 1}, {-1, 1, 1}, {0, 1, 0}, {0, 1, -5}}) {
            try {
                b.transfer(bad[0], bad[1], bad[2]);
                fail("accepted " + java.util.Arrays.toString(bad));
            } catch (IllegalArgumentException expected) {
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Banks.create("magic", 2, 10));
        assertThrows(IllegalArgumentException.class, () -> Banks.create("global", 1, 10));
        assertThrows(IllegalArgumentException.class, () -> Banks.create("global", 2, -1));
    }

    // ---- concurrency ------------------------------------------------------

    @Test
    public void deadlockFreeBanksStayConsistentUnderHeavyContention() throws Exception {
        for (String name : DEADLOCK_FREE) {
            for (int seed = 0; seed < 3; seed++) {
                // 10 accounts and 8 threads: almost every transfer collides with another
                AbstractBank bank = Banks.create(name, 10, 1_000_00);
                Simulator.Result r = Simulator.run(bank, 8, 20_000, seed, 200);
                assertFalse(name + " deadlocked", r.deadlocked());
                assertTrue(name + " lost or created money", r.moneyConserved());
                assertTrue(name + " ledger does not match balances", r.ledgerMatches());
                assertEquals(name, 8 * 20_000, r.attempted());
                assertEquals(name, r.attempted(), r.completed() + r.rejected());
                assertEquals(name, r.completed(), bank.ledger().size());
            }
        }
    }

    @Test
    public void unsafeBankCorruptsBalancesWhenThreadsRace() throws Exception {
        int corrupted = 0;
        for (int seed = 0; seed < 3; seed++) {
            Simulator.Result r = Simulator.run(Banks.create("unsafe", 10, 1_000_00), 4, 20_000, seed, 200);
            if (!r.moneyConserved() || !r.ledgerMatches()) corrupted++;
        }
        assertTrue("the race never showed up in 3 runs", corrupted > 0);
    }

    @Test
    public void unsafeBankIsFineOnOneThread() throws Exception {
        Simulator.Result r = Simulator.run(Banks.create("unsafe", 10, 1_000_00), 1, 20_000, 1, 200);
        assertTrue(r.moneyConserved() && r.ledgerMatches());
    }

    /**
     * Forces the classic deadlock deterministically: both threads take their first
     * lock, meet at a barrier, then each asks for the lock the other holds.
     */
    private static Simulator.Result oppositeTransfersWithBarrier(String name, AtomicBoolean reachedBarrierTogether)
            throws Exception {
        AbstractBank bank = Banks.create(name, 2, 1000);
        CyclicBarrier barrier = new CyclicBarrier(2);
        bank.setBetweenLocks(() -> {
            try {
                barrier.await(300, TimeUnit.MILLISECONDS);
                reachedBarrierTogether.set(true);
            } catch (TimeoutException | BrokenBarrierException e) {
                // the other thread is blocked on our first lock (ordered locking): carry on
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        List<Thread> threads = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        long[][] moves = {{0, 1}, {1, 0}};
        for (long[] m : moves) {
            Thread t = new Thread(() -> {
                try {
                    bank.transfer((int) m[0], (int) m[1], 10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "transfer-" + m[0] + "-to-" + m[1]);
            threads.add(t);
        }
        for (Thread t : threads) { t.start(); ids.add(t.threadId()); }
        List<String> found = List.of();
        long deadline = System.currentTimeMillis() + 3000;
        while (threads.stream().anyMatch(Thread::isAlive) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            List<String> d = DeadlockDetector.find(ids);
            if (!d.isEmpty()) {
                found = d;
                threads.forEach(Thread::interrupt);
            }
        }
        for (Thread t : threads) t.join(1000);
        assertFalse("threads still running", threads.stream().anyMatch(Thread::isAlive));
        return new Simulator.Result(name, 2, 2, 2, bank.ledger().size(), 0, 0, found, 2000, bank.total(),
                java.util.Arrays.equals(bank.ledger().replay(new long[] {1000, 1000}), bank.balances()), 0);
    }

    @Test
    public void naiveLockingDeadlocksAndTheDetectorFindsTheCycle() throws Exception {
        AtomicBoolean together = new AtomicBoolean();
        Simulator.Result r = oppositeTransfersWithBarrier("naive", together);
        assertTrue(together.get());
        assertEquals(List.of(
                "transfer-0-to-1 waits for ReentrantLock owned by transfer-1-to-0",
                "transfer-1-to-0 waits for ReentrantLock owned by transfer-0-to-1"), r.deadlock());
        // Interrupting the first thread releases its lock, and the second thread may get it
        // and finish before its own interrupt arrives: breaking a deadlock needs only one victim.
        // Either way, an interrupted transfer must never move money halfway.
        assertTrue("completed " + r.completed(), r.completed() <= 1);
        assertTrue(r.moneyConserved() && r.ledgerMatches());
    }

    @Test
    public void orderedLockingCannotDeadlockOnTheSameSchedule() throws Exception {
        AtomicBoolean together = new AtomicBoolean();
        Simulator.Result r = oppositeTransfersWithBarrier("ordered", together);
        assertFalse("with ordered locks the second thread can't take its first lock", together.get());
        assertTrue(r.deadlock().isEmpty());
        assertEquals(2, r.completed());
        assertTrue(r.moneyConserved() && r.ledgerMatches());
    }

    @Test
    public void tryLockBacksOffInsteadOfDeadlocking() throws Exception {
        AtomicBoolean together = new AtomicBoolean();
        Simulator.Result r = oppositeTransfersWithBarrier("trylock", together);
        assertTrue(together.get());                       // both held their first lock at the same time...
        assertTrue(r.deadlock().isEmpty());               // ...yet no deadlock: they released and retried
        assertEquals(2, r.completed());
        assertTrue(r.moneyConserved() && r.ledgerMatches());
    }

    @Test(timeout = 20_000)
    public void simulatorWatchdogStopsADeadlockedRun() throws Exception {
        // 2 accounts and 4 threads doing naive locking: opposite transfers collide constantly
        Simulator.Result r = Simulator.run(Banks.create("naive", 2, 1_000_00), 4, 200_000, 1, 100);
        assertTrue("expected a deadlock with this much contention", r.deadlocked());
        assertTrue(r.completed() < r.attempted() || r.attempted() < 4 * 200_000);
        assertTrue(r.moneyConserved() && r.ledgerMatches());
    }

    @Test
    public void detectorReportsNothingWhenThereIsNoDeadlock() {
        assertTrue(DeadlockDetector.find(Set.of(Thread.currentThread().threadId())).isEmpty());
    }

    @Test(timeout = 10_000)
    public void detectorOnlyReportsTheThreadsItWasAskedAbout() throws Exception {
        // Deadlock two other threads on purpose...
        AbstractBank bank = Banks.create("naive", 2, 1000);
        CyclicBarrier barrier = new CyclicBarrier(2);
        bank.setBetweenLocks(() -> {
            try { barrier.await(); } catch (Exception e) { Thread.currentThread().interrupt(); }
        });
        Thread a = new Thread(() -> { try { bank.transfer(0, 1, 1); } catch (InterruptedException e) { } }, "a");
        Thread b = new Thread(() -> { try { bank.transfer(1, 0, 1); } catch (InterruptedException e) { } }, "b");
        a.start();
        b.start();
        while (DeadlockDetector.find(Set.of(a.threadId(), b.threadId())).isEmpty()) Thread.sleep(20);
        try {
            // ...then ask about threads that are not part of it.
            assertTrue(DeadlockDetector.find(Set.of(Thread.currentThread().threadId())).isEmpty());
            assertEquals(1, DeadlockDetector.find(Set.of(a.threadId())).size());
        } finally {
            a.interrupt();
            b.interrupt();
            a.join();
            b.join();
        }
    }

    // ---- ledger -------------------------------------------------------------

    @Test
    public void ledgerSequenceNumbersAreUniqueUnderConcurrency() throws Exception {
        AbstractBank b = Banks.create("ordered", 50, 1_000_00);
        Simulator.Result r = Simulator.run(b, 8, 10_000, 5, 200);
        Set<Long> seqs = new HashSet<>();
        for (Ledger.Entry e : b.ledger().entries()) seqs.add(e.seq());
        assertEquals(r.completed(), seqs.size());
        assertEquals(r.completed() - 1, (long) java.util.Collections.max(seqs));   // 0 .. n-1, no gaps
    }

    @Test
    public void ledgerReplayDetectsTampering() throws Exception {
        AbstractBank b = Banks.create("global", 2, 100);
        b.transfer(0, 1, 30);
        assertArrayEquals(new long[] {70, 130}, b.ledger().replay(new long[] {100, 100}));
        b.accounts[1].balance += 5;                       // money from nowhere
        assertFalse(java.util.Arrays.equals(b.ledger().replay(new long[] {100, 100}), b.balances()));
    }
}
