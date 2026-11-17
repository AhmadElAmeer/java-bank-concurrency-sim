package bank;

import java.util.Locale;

/**
 * Command line:
 *   java -cp build/classes/java/main bank.Main bench [transfersPerThread]
 *   java -cp build/classes/java/main bank.Main run <bank> <threads> <accounts> <transfersPerThread>
 */
public final class Main {
    private static final long OPENING = 1_000_00;   // 1,000.00 per account

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "bench" -> bench(args.length > 1 ? Integer.parseInt(args[1]) : 50_000);
            case "run" -> {
                if (args.length != 5) { usage(); System.exit(2); }
                print(header());
                print(row(Simulator.run(Banks.create(args[1], Integer.parseInt(args[3]), OPENING),
                        Integer.parseInt(args[2]), Integer.parseInt(args[4]), 42, 200)));
            }
            default -> { usage(); System.exit(2); }
        }
    }

    static final int RUNS = 3;

    static void bench(int perThread) throws InterruptedException {
        print("Each worker attempts " + perThread + " random transfers; opening balance 1,000.00 per account.");
        print("Every configuration runs " + RUNS + " times after a warm-up; speed is the median, failures are counted.");
        print("Cores available: " + Runtime.getRuntime().availableProcessors());
        for (String name : Banks.NAMES)                 // warm up the JIT so the first rows aren't penalised
            Simulator.run(Banks.create(name, 100, OPENING), 2, perThread / 5, 7, 200);
        print("");
        print(String.format("%-8s %8s %8s %16s %10s %12s %12s %10s", "bank", "accounts", "threads",
                "median transf/s", "deadlocks", "money wrong", "ledger wrong", "retries"));
        for (int accounts : new int[] {10, 1000}) {
            for (String name : Banks.NAMES) {
                for (int threads : new int[] {1, 2, 4, 8}) {
                    double[] rates = new double[RUNS];
                    int dead = 0, money = 0, ledger = 0;
                    long retries = 0;
                    String example = null;
                    for (int run = 0; run < RUNS; run++) {
                        Simulator.Result r = Simulator.run(Banks.create(name, accounts, OPENING), threads, perThread, 42 + run * 100, 200);
                        rates[run] = r.transfersPerSecond();
                        if (r.deadlocked()) { dead++; if (example == null) example = String.join("; ", r.deadlock()); }
                        if (!r.moneyConserved()) money++;
                        if (!r.ledgerMatches()) ledger++;
                        retries += r.retries();
                    }
                    java.util.Arrays.sort(rates);
                    print(String.format("%-8s %8d %8d %16s %10s %12s %12s %10s", name, accounts, threads,
                            dead > 0 ? "(deadlocked)" : String.format("%,.0f", rates[RUNS / 2]),
                            dead + "/" + RUNS, money + "/" + RUNS, ledger + "/" + RUNS,
                            retries > 0 ? String.format("%,d", retries / RUNS) : "-"));
                    if (example != null) print("         e.g. " + example);
                }
            }
        }
    }

    static String header() {
        return String.format("%-8s %8s %8s %12s %12s %10s %12s %10s %8s %8s",
                "bank", "accounts", "threads", "attempted", "completed", "seconds", "transfers/s",
                "deadlock", "money", "ledger");
    }

    static String row(Simulator.Result r) {
        String money = r.moneyConserved() ? "ok" : String.format("%+d", r.totalAfter() - r.totalBefore());
        return String.format("%-8s %8d %8d %12d %12d %10.3f %12.0f %10s %8s %8s%s",
                r.bank(), r.accounts(), r.threads(), r.attempted(), r.completed(), r.seconds(),
                r.transfersPerSecond(), r.deadlocked() ? "YES" : "no", money, r.ledgerMatches() ? "ok" : "BROKEN",
                r.retries() > 0 ? "  retries=" + r.retries() : "");
    }

    static void usage() {
        System.err.println("usage: bank.Main bench [transfersPerThread]");
        System.err.println("       bank.Main run <unsafe|global|naive|ordered|trylock> <threads> <accounts> <transfersPerThread>");
    }

    private static void print(String s) { System.out.println(s); }
}
