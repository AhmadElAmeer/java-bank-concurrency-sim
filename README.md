# java-bank-concurrency-sim

A Java bank that moves money between accounts on many threads, implemented five ways, from broken to correct. It reproduces the two classic concurrency bugs (**lost updates** and **deadlock**), detects them automatically, and measures what each fix costs.

```
$ java -cp build/classes/java/main bank.Main run naive 4 10 20000
bank     accounts  threads    attempted    completed    seconds  transfers/s   deadlock    money   ledger
naive          10        4        17558        17554      0.246        71470        YES       ok       ok
```

Four threads, ten accounts, naive locking: after 17,558 transfers two threads were each waiting for the lock the other held. The JVM's deadlock detector found the cycle, the watchdog interrupted the threads, and the books still balanced.

## Problem

`transfer(from, to, amount)` looks trivial: subtract here, add there. With several threads it breaks in two well-known ways:
1. **Race condition:** two threads read the same balance, both write back, and one update disappears. Money is created or destroyed.
2. **Deadlock:** lock the source, then the destination. Thread 1 does A→B while thread 2 does B→A. Each holds one lock and waits forever for the other.

Both bugs are intermittent. They hide in tests and appear in production. This project makes them appear reliably, shows how each fix prevents them, and proves the fixes hold under heavy contention.

## Overview

| strategy | how `transfer` is protected | outcome |
|---|---|---|
| `unsafe` | not at all | money is created or lost as soon as two threads run |
| `global` | one lock for the whole bank | correct, no deadlock; all transfers run one at a time |
| `naive` | lock source, then destination | correct when it finishes, but **deadlocks** |
| `ordered` | lock the lower account id first | correct, deadlock-free: no cycle of waiting threads is possible |
| `trylock` | lock the first, *try* the second; if busy, release, back off and retry | correct, deadlock-free; pays with retries |

Around them:
- `Ledger`: a thread-safe, append-only record of completed transfers. Replaying it from the opening balances must reproduce the final balances; if it doesn't, the bank lost or invented money.
- `DeadlockDetector`: asks the JVM (`ThreadMXBean.findDeadlockedThreads`) which threads are in a lock cycle, and describes it.
- `Simulator`: runs random transfers on N threads with a watchdog. On deadlock it interrupts the workers (every lock is taken with `lockInterruptibly`, so they can escape), then checks total money and the ledger.

## Key features

- Five interchangeable strategies behind one `Bank` interface.
- Automatic deadlock detection and recovery. Each report names the cycle, e.g. *worker-0 waits for ReentrantLock owned by worker-1; worker-1 waits for ReentrantLock owned by worker-0*.
- Two independent integrity checks after every run: total money conserved, and ledger replay equals balances.
- Insufficient funds are rejected atomically (no partial transfers), and invalid arguments are refused.
- A deterministic deadlock test: a test hook between the two lock acquisitions lets a `CyclicBarrier` force the exact bad interleaving every time.
- A benchmark comparing every strategy at 1–8 threads under high (10 accounts) and low (1,000 accounts) contention, repeated 3 times after a JIT warm-up.

## Tech stack

- Java 21: `ReentrantLock`, `CyclicBarrier`, atomics, `ConcurrentLinkedQueue`, records, switch expressions, `ThreadMXBean`
- Gradle 8.14 (wrapper included)
- JUnit 4.13

## Architecture

```
Bank (interface)
  └─ AbstractBank: accounts[], argument checks, move() + ledger.record()
       ├─ Unsafe        read … yield … write   (race window widened on purpose)
       ├─ GlobalLock    bankLock { move }
       ├─ NaiveLocks    from.lock { to.lock { move } }               ← can deadlock
       ├─ OrderedLocks  min(id).lock { max(id).lock { move } }       ← global lock order
       └─ TryLocks      from.lock { to.tryLock() ? move : release + back off + retry }

Simulator ─ N worker threads ─► bank.transfer(...)
    │ every 200 ms: DeadlockDetector.find(workers) ─► interrupt all if a cycle is found
    └ afterwards: total before == total after?  ledger.replay(opening) == balances?
```

**Why ordered locking can't deadlock.** A deadlock needs a cycle: T1 holds A and waits for B, while T2 holds B and waits for A. If every thread acquires locks in increasing id order, a thread holding lock *k* only ever waits for a lock with an id greater than *k*. Following the "waits for" arrows, ids strictly increase, so they can never come back to where they started. No cycle, no deadlock.

**Why try-lock can't deadlock (but can livelock).** A thread never waits while holding a lock it can't use. If the second lock is busy, it releases the first. Two threads could keep colliding and backing off forever (livelock). A random back-off makes repeated collisions increasingly unlikely; the benchmark counts the retries.

## Installation

Requires JDK 21.

```bash
git clone https://github.com/AhmadElAmeer/java-bank-concurrency-sim.git
cd java-bank-concurrency-sim
./gradlew build          # downloads Gradle 8.14.3 and JUnit on first run, compiles, runs the tests
```

## Usage

```bash
./gradlew run --args="bench"                       # full comparison (about 20 s)
./gradlew run --args="bench 20000"                 # quicker, fewer transfers per worker
./gradlew run --args="run naive 4 10 20000"        # one run: <bank> <threads> <accounts> <transfers per thread>
./gradlew run --args="run unsafe 4 10 20000"
```

## Results

From [docs/bench.txt](docs/bench.txt): each worker attempts 100,000 random transfers, 3 runs per row, on a **2-core** Intel Xeon @ 2.10 GHz. Speed is the median of the 3 runs; failures are counted out of 3.

**Correctness** (the part that's deterministic in practice):

| strategy | money wrong (≥ 2 threads) | ledger wrong | deadlocked |
|---|---|---|---|
| unsafe | **18 / 18 runs** | 18 / 18 | 0 |
| global | 0 / 18 | 0 / 18 | 0 |
| naive | 0 | 0 | **12 / 18 runs**: 9/9 at 10 accounts, 3/9 at 1,000 accounts (all at 8 threads) |
| ordered | 0 / 18 | 0 / 18 | 0 |
| trylock | 0 / 18 | 0 / 18 | 0 (retries: up to 4,307 per run at 10 accounts, 8 threads) |

**Speed** (transfers per second, median):

| strategy | 10 accounts, 4 threads | 1,000 accounts, 4 threads |
|---|---:|---:|
| unsafe | 1,501,847 (wrong answers) | 1,509,684 (wrong answers) |
| global | **7,985,210** | **10,435,936** |
| ordered | 5,009,480 | 2,487,982 |
| trylock | 6,016,170 | 3,120,259 |

What the numbers show:

1. **The race is not rare.** With two or more threads the unsafe bank got the total wrong in *every* run, at both contention levels, creating 228,647 cents in one 4-thread run of an earlier benchmark. The `Thread.yield()` in its read-modify-write widens the window, but the bug is the same one any unsynchronized `balance -= amount` has.
2. **Deadlock depends on contention, which is what makes it dangerous.** With 10 accounts, naive locking deadlocked in all 9 multi-threaded runs. With 1,000 accounts it deadlocked only at 8 threads (and in one earlier run, once at 4 threads). A test suite with low contention could pass for months. Most cycles were between two threads, with others queued behind them. In an earlier run three threads formed the cycle (worker-7 → worker-4 → worker-2 → worker-7).
3. **On 2 cores, the single global lock was the fastest correct strategy.** That's the opposite of the textbook intuition, and it's real here: each transfer holds its lock for well under a microsecond, so one lock that stays hot in cache beats taking two per-account locks that are spread across memory. Fine-grained locking pays off when critical sections are longer and there are more cores to run them in parallel, neither of which this machine has. The honest conclusion is to measure before choosing.
4. **The unsafe bank is also the slowest.** Its deliberate `Thread.yield()` dominates the cost, so its speed numbers say nothing about real unsynchronized code. It's there to demonstrate the bug, not to be benchmarked.
5. Speed numbers vary between runs. For example, the global lock at 1,000 accounts and 2 threads was consistently slower than at 4 threads in both full runs, possibly because two threads on two cores hand the lock across cores on nearly every transfer. Treat speed differences under about 2× as noise; the correctness columns are what to rely on.

## Testing

```bash
./gradlew test           # 14 tests, a few seconds
```

- **Single-threaded behaviour, for every strategy:** money moves and is recorded; transferring exactly the balance is allowed but one cent more is rejected with no side effects; unknown accounts, same-account transfers and non-positive amounts are refused.
- **Contention:** `global`, `ordered` and `trylock` each run 8 threads × 20,000 transfers over 10 accounts, 3 times. No deadlock, money conserved, ledger matches, ledger size equals completed transfers.
- **The bugs, reproduced:**
  - the unsafe bank corrupts the books with 4 threads but not with 1;
  - naive locking deadlocks *deterministically* when a barrier forces both threads to hold their first lock, and the detector reports exactly the two-thread cycle;
  - with the identical schedule, ordered locking can't even reach the barrier, and try-lock reaches it but backs off, so neither deadlocks;
  - the simulator's watchdog detects and ends a deadlocked run on its own.
- **Ledger:** sequence numbers stay unique and gap-free under 8 threads, and replay detects a tampered balance.
- **Detector scope:** with two other threads genuinely deadlocked, the detector ignores them when asked about different threads.

The suite was run 6 times in a row with no failures. Eight bugs were planted one at a time:

- ordered locking reverted to source-first;
- try-lock waiting instead of trying;
- exact balance rejected;
- transfers not recorded;
- the watchdog not interrupting;
- the global bank without its lock;
- the detector ignoring which threads it was asked about;
- a non-atomic ledger sequence counter.

All eight are caught. The detector-scope one got through at first, so the "other threads deadlocked" test was added.

## Design decisions and challenges

- **Making deadlock testable.** A real deadlock depends on timing, so a test that simply runs transfers might never see one, or might hang forever. The `betweenLocks` hook plus a `CyclicBarrier` forces the exact interleaving, and interruptible locks let the test recover instead of hanging.
- **One survivor after recovery.** The first version of the deadlock test expected 0 completed transfers after recovery, and failed 1 time in 3. The watchdog interrupts threads one at a time: once the first is interrupted it releases its lock, and the second can finish its transfer before its own interrupt arrives. That's correct behaviour (breaking a deadlock only needs one victim), so the test now allows 0 or 1. The invariant that matters, no money moved halfway, is still checked.
- **Two integrity checks instead of one.** A conserved total can hide errors: a lost debit and a lost credit cancel out. The ledger replay checks every account, not just the sum.
- **`ReentrantLock` instead of `synchronized`.** It allows `lockInterruptibly` (recovery), `tryLock` (the back-off strategy), and still shows up in the JVM's deadlock detection.
- **Benchmark hygiene.** A JIT warm-up, 3 runs per configuration, and medians. The first version measured single millisecond-long runs, and its speed numbers were mostly warm-up noise.

## Future improvements

- `StampedLock` / optimistic reads for a read-heavy "balance inquiry" workload.
- A lock-free version: per-account `AtomicLong` with a compare-and-set retry loop for single-account updates, and a discussion of why two-account transfers can't simply be made atomic that way.
- Java 21 virtual threads, to see how the strategies behave with 10,000 concurrent transfers.
- The same benchmark on a machine with more cores, to find where fine-grained locking overtakes the global lock.

## What this project teaches

- Race conditions and why `balance -= amount` isn't atomic.
- The four conditions for deadlock, and breaking the cycle with a global lock order.
- `ReentrantLock`, `lockInterruptibly`, `tryLock`, `CyclicBarrier`, atomics and concurrent collections.
- Detecting deadlocks at runtime with `ThreadMXBean`.
- Testing concurrent code deterministically, and handling legitimate nondeterminism in tests.
- Measuring before believing: the "obvious" fine-grained design lost on this hardware.

## License

MIT — see [LICENSE](LICENSE).
