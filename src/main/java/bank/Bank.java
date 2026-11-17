package bank;

/** A bank that moves money between accounts. Implementations differ only in how they make transfer() safe. */
public interface Bank {
    /**
     * Move {@code amount} cents from one account to another.
     *
     * @return true if the transfer happened, false if {@code from} had insufficient funds
     * @throws IllegalArgumentException for unknown accounts, from == to, or amount <= 0
     * @throws InterruptedException if the thread was interrupted while waiting for a lock
     */
    boolean transfer(int from, int to, long amount) throws InterruptedException;

    long balance(int account);

    /** Sum of all balances. Only meaningful when no transfers are running. */
    long total();

    int size();

    Ledger ledger();

    String name();
}
