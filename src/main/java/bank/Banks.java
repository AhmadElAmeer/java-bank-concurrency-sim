package bank;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** The five strategies, from broken to correct. */
public final class Banks {
    private Banks() {}

    public static AbstractBank create(String name, int accounts, long openingBalance) {
        return switch (name) {
            case "unsafe" -> new Unsafe(accounts, openingBalance);
            case "global" -> new GlobalLock(accounts, openingBalance);
            case "naive" -> new NaiveLocks(accounts, openingBalance);
            case "ordered" -> new OrderedLocks(accounts, openingBalance);
            case "trylock" -> new TryLocks(accounts, openingBalance);
            default -> throw new IllegalArgumentException("unknown bank: " + name);
        };
    }

    public static final String[] NAMES = {"unsafe", "global", "naive", "ordered", "trylock"};

    /**
     * No synchronization. The read-check-write in move() races between threads,
     * so updates get lost and money appears or disappears. Thread.yield() widens
     * the race window so the bug shows up reliably in a demo.
     */
    static final class Unsafe extends AbstractBank {
        Unsafe(int n, long b) { super(n, b); }
        @Override public String name() { return "unsafe"; }

        @Override
        protected boolean doTransfer(Account from, Account to, long amount) {
            long f = from.balance;
            if (f < amount) return false;
            Thread.yield();
            from.balance = f - amount;
            long t = to.balance;
            Thread.yield();
            to.balance = t + amount;
            ledger.record(from.id, to.id, amount);
            return true;
        }
    }

    /** One lock for the whole bank: correct and deadlock-free, but every transfer waits for every other. */
    static final class GlobalLock extends AbstractBank {
        private final ReentrantLock bankLock = new ReentrantLock();
        GlobalLock(int n, long b) { super(n, b); }
        @Override public String name() { return "global"; }

        @Override
        protected boolean doTransfer(Account from, Account to, long amount) throws InterruptedException {
            bankLock.lockInterruptibly();
            try {
                return move(from, to, amount);
            } finally {
                bankLock.unlock();
            }
        }
    }

    /**
     * Lock the source, then the destination. Two opposite transfers (A→B and B→A)
     * can each hold their first lock and wait forever for the other: a deadlock.
     */
    static final class NaiveLocks extends AbstractBank {
        NaiveLocks(int n, long b) { super(n, b); }
        @Override public String name() { return "naive"; }

        @Override
        protected boolean doTransfer(Account from, Account to, long amount) throws InterruptedException {
            from.lock.lockInterruptibly();
            try {
                betweenLocks.run();
                to.lock.lockInterruptibly();
                try {
                    return move(from, to, amount);
                } finally {
                    to.lock.unlock();
                }
            } finally {
                from.lock.unlock();
            }
        }
    }

    /**
     * Always lock the lower account id first. Every thread acquires locks in the
     * same global order, so a cycle of waiting threads (and so a deadlock) is impossible.
     */
    static final class OrderedLocks extends AbstractBank {
        OrderedLocks(int n, long b) { super(n, b); }
        @Override public String name() { return "ordered"; }

        @Override
        protected boolean doTransfer(Account from, Account to, long amount) throws InterruptedException {
            Account first = from.id < to.id ? from : to;
            Account second = first == from ? to : from;
            first.lock.lockInterruptibly();
            try {
                betweenLocks.run();
                second.lock.lockInterruptibly();
                try {
                    return move(from, to, amount);
                } finally {
                    second.lock.unlock();
                }
            } finally {
                first.lock.unlock();
            }
        }
    }

    /**
     * Take the first lock, then *try* the second; if it's busy, release everything,
     * back off for a random moment and retry. No deadlock, but contended transfers
     * may retry many times.
     */
    static final class TryLocks extends AbstractBank {
        final AtomicLong retries = new AtomicLong();
        TryLocks(int n, long b) { super(n, b); }
        @Override public String name() { return "trylock"; }

        @Override
        protected boolean doTransfer(Account from, Account to, long amount) throws InterruptedException {
            while (true) {
                from.lock.lockInterruptibly();
                try {
                    betweenLocks.run();
                    if (to.lock.tryLock()) {
                        try {
                            return move(from, to, amount);
                        } finally {
                            to.lock.unlock();
                        }
                    }
                } finally {
                    from.lock.unlock();
                }
                retries.incrementAndGet();
                TimeUnit.MICROSECONDS.sleep(ThreadLocalRandom.current().nextInt(1, 50));
            }
        }
    }
}
