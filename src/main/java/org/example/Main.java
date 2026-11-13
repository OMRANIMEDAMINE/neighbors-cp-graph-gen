package org.example;

import ilog.concert.IloException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Benchmark driver: for each degree sequence, runs every model of {@link CpModels} listed
 * in {@link #columns()} and prints one table row (solutions, time, peak memory per model).
 *
 * <p>Usage: {@code Main [seq ...]}, one degree sequence per argument, degrees separated by
 * commas, e.g. {@code Main 2,2,2,3,3,4 3,3,3,3}. Every sequence is sorted in increasing
 * order before it is run. Without arguments the {@link #DEFAULT_SEQUENCES} are run.
 */
public class Main {

    // =========================================================================
    //  CONFIGURATION
    // =========================================================================

    /**
     * Sequences run when no argument is given, in increasing order: vertex i has degree
     * deg[i]. Non-graphic sequences are skipped.
     */
    private static final int[][] DEFAULT_SEQUENCES = {
            //regular(6, 4), regular(7, 4), regular(8, 4), regular(9, 4), regular(10, 4), regular(12, 4),
           // {2, 2, 2, 3, 3, 4}, {2, 2, 2, 2, 3, 3, 3, 3}, {2, 2, 3, 3, 3, 4, 4, 5},
            {1,2,2,3},
    };

    /**
     * true = connected graphs only (OptRevLex models only, upper off-diagonal constraint:
     * correct for regular sequences, may lose graphs for non-regular ones); false = all graphs.
     */
    private static final boolean CONNECTIVITY = false;

    /** true = print every graph found (slower, and included in the time). */
    private static final boolean PRINT_SOLUTIONS = true;

    /** A model of {@link CpModels}: solves one degree sequence, printing under title if not null. */
    private interface Model {
        Result run(int[] deg, String title) throws IloException;
    }

    /** One table column: its header and the model it runs. */
    private record Column(String name, Model model) {}

    /**
     * The table columns. Connectivity is only valid with OptRevLex, so the OptLex models
     * are not run when CONNECTIVITY is on.
     */
    private static List<Column> columns() {
        if (CONNECTIVITY) {
            return List.of(
                    new Column("OptRevLexAdjCon",    (d, t) -> CpModels.optRevLexAdj(d, true, t)),
                    new Column("OptRevLexNeiDirCon", (d, t) -> CpModels.optRevLexNeiDir(d, true, t)));
        }
        return List.of(
                new Column("OptLexAdj",       CpModels::optLexAdj),
                new Column("OptLexNeiDir",    CpModels::optLexNeiDir),
                new Column("OptRevLexAdj",    (d, t) -> CpModels.optRevLexAdj(d, false, t)),
                new Column("OptRevLexNeiDir", (d, t) -> CpModels.optRevLexNeiDir(d, false, t)));
    }

    private static final List<Column> COLUMNS = columns();

    private static final int W_COL = 20;   // width of a result column

    // =========================================================================
    //  RUN
    // =========================================================================

    public static void main(String[] args) throws IloException {
        int[][] sequences = Arrays.stream(args.length == 0 ? DEFAULT_SEQUENCES : parse(args))
                .map(d -> Arrays.stream(d).sorted().toArray())   // increasing order
                .toArray(int[][]::new);
        int wName = Math.max(10, 2 + Arrays.stream(sequences).mapToInt(d -> name(d).length()).max().orElse(0));

        System.out.println("Connectivity: " + (CONNECTIVITY ? "ON" : "OFF")
                + "   |   cell = solutions (time), then peak memory");
        printRow(wName, "Sequence", COLUMNS.stream().map(Column::name).toList());
        printRule(wName, '=');

        for (int[] deg : sequences) {
            String name = name(deg);
            String error = check(deg);
            if (error != null) {
                System.out.println(" " + name + " skipped: " + error);
                continue;
            }
            List<Result> results = new ArrayList<>();
            for (Column c : COLUMNS) {
                results.add(c.model().run(deg, PRINT_SOLUTIONS ? name + " " + c.name() : null));
            }
            printRow(wName, name, results.stream()
                    .map(r -> String.format("%,d (%.2fs)", r.count(), r.timeMs() / 1000.0)).toList());
            printRow(wName, "  memory", results.stream().map(r -> memory(r.peakMemory())).toList());
            printRule(wName, '-');
        }
    }

    // =========================================================================
    //  DEGREE SEQUENCES
    // =========================================================================

    /** d repeated n times. */
    private static int[] regular(int n, int d) {
        int[] deg = new int[n];
        Arrays.fill(deg, d);
        return deg;
    }

    /** Each argument "4,3,3,2" is one sequence. */
    private static int[][] parse(String[] args) {
        return Arrays.stream(args)
                .map(a -> Arrays.stream(a.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray())
                .toArray(int[][]::new);
    }

    /** "N8_d3" for a regular sequence, else e.g. "N6_222334" ('.'-separated if a degree > 9). */
    private static String name(int[] deg) {
        if (Arrays.stream(deg).allMatch(d -> d == deg[0])) return "N" + deg.length + "_d" + deg[0];
        String sep = Arrays.stream(deg).anyMatch(d -> d > 9) ? "." : "";
        return "N" + deg.length + "_" + String.join(sep, Arrays.stream(deg).mapToObj(String::valueOf).toList());
    }

    /** Returns why the sequence cannot be run, or null if it can. */
    private static String check(int[] deg) {
        int n = deg.length;
        if (n < 2) return "N must be at least 2";
        for (int d : deg) {
            if (d < 0 || d >= n) return "every degree must be in [0, N-1]";
        }

        // Erdős–Gallai, on the degrees in decreasing order d_1 >= ... >= d_n: the sum is
        // even and for every k, d_1 + ... + d_k <= k(k-1) + sum_{i>k} min(d_i, k)
        int[] d = new int[n];
        for (int i = 0; i < n; i++) d[i] = deg[n - 1 - i];   // deg is increasing
        if (Arrays.stream(d).sum() % 2 != 0) return "the degree sum must be even";
        long left = 0;
        for (int k = 1; k <= n; k++) {
            left += d[k - 1];
            long right = (long) k * (k - 1);
            for (int i = k; i < n; i++) right += Math.min(d[i], k);
            if (left > right) return "not graphic (Erdős–Gallai fails at k = " + k + ")";
        }
        return null;
    }

    // =========================================================================
    //  TABLE
    // =========================================================================

    private static void printRow(int wName, String name, List<String> cells) {
        StringBuilder sb = new StringBuilder("|").append(pad(" " + name, wName)).append('|');
        for (String c : cells) sb.append(center(c, W_COL)).append('|');
        System.out.println(sb);
    }

    private static void printRule(int wName, char ch) {
        String c = String.valueOf(ch);
        System.out.println("+" + c.repeat(wName) + ("+" + c.repeat(W_COL)).repeat(COLUMNS.size()) + "+");
    }

    /** Bytes as KB / MB / GB. */
    private static String memory(long bytes) {
        if (bytes >= 1L << 30) return String.format("%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format("%.1f MB", bytes / (double) (1L << 20));
        return String.format("%.1f KB", bytes / 1024.0);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s.substring(0, width) : s + " ".repeat(width - s.length());
    }

    private static String center(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        int left = (width - s.length()) / 2;
        return " ".repeat(left) + s + " ".repeat(width - s.length() - left);
    }
}
