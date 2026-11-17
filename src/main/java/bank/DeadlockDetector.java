package bank;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Asks the JVM which threads are stuck in a cycle of lock waits. */
public final class DeadlockDetector {
    private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();

    private DeadlockDetector() {}

    /**
     * Describe each deadlocked thread among {@code watched}, e.g.
     * "worker-1 waits for ReentrantLock owned by worker-0". Empty if none.
     */
    public static List<String> find(Set<Long> watched) {
        long[] ids = MX.findDeadlockedThreads();     // covers ReentrantLock as well as synchronized
        List<String> out = new ArrayList<>();
        if (ids == null) return out;
        for (ThreadInfo info : MX.getThreadInfo(ids)) {
            if (info == null || !watched.contains(info.getThreadId())) continue;
            String lock = info.getLockName() == null ? "a lock" : info.getLockName().replaceAll("@.*", "")
                    .replace("java.util.concurrent.locks.ReentrantLock$NonfairSync", "ReentrantLock");
            out.add(info.getThreadName() + " waits for " + lock + " owned by " + info.getLockOwnerName());
        }
        out.sort(null);
        return out;
    }
}
