package bank;

import java.util.concurrent.locks.ReentrantLock;

/** One account. Balances are in cents; the lock is used by the locking banks. */
public final class Account {
    final int id;
    long balance;                              // guarded by `lock` (or by the bank's strategy)
    final ReentrantLock lock = new ReentrantLock();

    Account(int id, long balance) {
        this.id = id;
        this.balance = balance;
    }
}
