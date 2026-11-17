package bank;

/** Accounts, argument checking and the ledger, shared by every strategy. */
abstract class AbstractBank implements Bank {
    protected final Account[] accounts;
    protected final Ledger ledger = new Ledger();
    /** Test hook: runs after the first lock is taken and before the second. */
    protected volatile Runnable betweenLocks = () -> {};

    AbstractBank(int accounts, long openingBalance) {
        if (accounts < 2) throw new IllegalArgumentException("need at least 2 accounts");
        if (openingBalance < 0) throw new IllegalArgumentException("opening balance must be >= 0");
        this.accounts = new Account[accounts];
        for (int i = 0; i < accounts; i++) this.accounts[i] = new Account(i, openingBalance);
    }

    @Override
    public final boolean transfer(int from, int to, long amount) throws InterruptedException {
        if (from < 0 || from >= accounts.length || to < 0 || to >= accounts.length)
            throw new IllegalArgumentException("unknown account");
        if (from == to) throw new IllegalArgumentException("cannot transfer to the same account");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        return doTransfer(accounts[from], accounts[to], amount);
    }

    protected abstract boolean doTransfer(Account from, Account to, long amount) throws InterruptedException;

    /** Move the money and record it. The caller must already hold whatever protects both accounts. */
    protected final boolean move(Account from, Account to, long amount) {
        if (from.balance < amount) return false;
        from.balance -= amount;
        to.balance += amount;
        ledger.record(from.id, to.id, amount);
        return true;
    }

    public void setBetweenLocks(Runnable hook) {
        this.betweenLocks = hook;
    }

    @Override public long balance(int account) { return accounts[account].balance; }
    @Override public int size() { return accounts.length; }
    @Override public Ledger ledger() { return ledger; }

    @Override
    public long total() {
        long sum = 0;
        for (Account a : accounts) sum += a.balance;
        return sum;
    }

    public long[] balances() {
        long[] b = new long[accounts.length];
        for (int i = 0; i < b.length; i++) b[i] = accounts[i].balance;
        return b;
    }
}
