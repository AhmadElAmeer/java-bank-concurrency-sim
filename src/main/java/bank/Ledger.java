package bank;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only record of completed transfers, safe to use from many threads.
 * Replaying it from the opening balances must reproduce the final balances;
 * if it doesn't, the bank lost or invented money.
 */
public final class Ledger {
    public record Entry(long seq, int from, int to, long amount) {}

    private final AtomicLong nextSeq = new AtomicLong();
    private final ConcurrentLinkedQueue<Entry> entries = new ConcurrentLinkedQueue<>();

    void record(int from, int to, long amount) {
        entries.add(new Entry(nextSeq.getAndIncrement(), from, to, amount));
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries);
    }

    public int size() {
        return entries.size();
    }

    /** Balances after applying every recorded transfer to the opening balances. */
    public long[] replay(long[] opening) {
        long[] b = opening.clone();
        for (Entry e : entries) {
            b[e.from()] -= e.amount();
            b[e.to()] += e.amount();
        }
        return b;
    }
}
