package org.example;

import java.util.Arrays;

/**
 * Benchmark driver: enumerates the graphs on N vertices for each N in {@link #N_VALUES},
 * with every configuration of {@link CpModels}, and prints the results as a text table
 * (one column per configuration, two lines per N).
 */
public class Main {

    // =========================================================================
    //  CONFIGURATION
    // =========================================================================

    /** Numbers of vertices N to run, one table row each (each N >= 2). */
    private static final int[] N_VALUES = {5};

    /**
     * CpModels.ANY_DEGREE = all graphs on N vertices;
     * d >= 1              = only d-regular graphs (every vertex has degree d).
     * An N with no d-regular graph (d >= N, or N·d odd) is skipped.
     */
    private static final int DEGREE = CpModels.ANY_DEGREE; // Or d

    /**
     * true  = also post the upper off-diagonal connectivity constraint (connected graphs only);
     * false = OptRevLex symmetry breaking only (connected or not).
     */
    private static final boolean CONNECTIVITY = false;

    /**
     * true  = print every solution (graph) found to the console, before the table row;
     * false = count solutions only. Printing slows the runs and is included in the time.
     */
    private static final boolean PRINT_SOLUTIONS = true;

    /** Table columns, in the same order as the results passed to printInstanceRows. */
    private static final String[] ALL_COLS = CONNECTIVITY
            ? new String[]{"OptRevLexAdjCon", "OptRevLexNeiCon"}
            : new String[]{"OptRevLexAdj", "OptRevLexNei"};

    /** Width of the instance-name column. */
    private static final int W_INST = 10;
    /** Width of each result column. */
    private static final int W_COL = 20;

    // =========================================================================
    //  ENTRY POINT
    // =========================================================================

    public static void main(String[] args) {
        runExperiments();
    }

    // =========================================================================
    //  EXPERIMENT RUNNER
    // =========================================================================

    /** Runs all configurations for each N and prints the table. */
    public static void runExperiments() {
        if (DEGREE != CpModels.ANY_DEGREE && DEGREE < 1) {
            throw new IllegalArgumentException("DEGREE must be CpModels.ANY_DEGREE or >= 1");
        }

        printBanner();
        printColumnHeader();
        printHRule('=');
        printGroupLabel(DEGREE == CpModels.ANY_DEGREE
                ? "All graphs  G_n" : DEGREE + "-regular graphs  K_n(" + DEGREE + ")");

        for (int n : N_VALUES) {
            String name = instanceName(n);
            String skip = whySkipped(n);
            if (skip != null) {
                System.out.println("  " + name + " skipped: " + skip);
                continue;
            }

            // Title shown above the printed solutions; null disables printing.
            Result rAdj = CpModels.testOptRevLexAdj(n, DEGREE, CONNECTIVITY,
                    PRINT_SOLUTIONS ? name + " " + ALL_COLS[0] : null);
            Result rNei = CpModels.testOptRevLexNei(n, DEGREE, CONNECTIVITY,
                    PRINT_SOLUTIONS ? name + " " + ALL_COLS[1] : null);

            printInstanceRows(name, new Result[]{rAdj, rNei});
            printHRule('-');
        }

        printFooter();
    }

    /** Row name: "N8" for all graphs, "N8_d3" when the degree is fixed. */
    private static String instanceName(int n) {
        return DEGREE == CpModels.ANY_DEGREE ? "N" + n : "N" + n + "_d" + DEGREE;
    }

    /** Returns why N cannot be run with the configured degree, or null if it can. */
    private static String whySkipped(int n) {
        if (n < 2) return "N must be at least 2";
        if (DEGREE == CpModels.ANY_DEGREE) return null;
        if (DEGREE >= n) return "degree " + DEGREE + " needs more than " + n + " vertices";
        if (n * DEGREE % 2 != 0) return "N·d must be even (each edge adds 2 to the degree sum)";
        return null;
    }

    // =========================================================================
    //  TABLE PRINTING
    // =========================================================================

    /** Prints the title and the legend explaining the cell contents. */
    private static void printBanner() {
        int w = tableWidth();
        System.out.println();
        System.out.println("=".repeat(w));
        System.out.println(center("Graph Generation on N Vertices — Benchmark", w));
        System.out.println(center("OptRevLex  ×  Connectivity", w));
        System.out.println("=".repeat(w));
        System.out.println();
        System.out.println("Metrics:  Row 1 = Solutions (time s)   |   Row 2 = Peak memory");
        System.out.println();
        System.out.println("  N values:                " + Arrays.toString(N_VALUES));
        System.out.println("  Degree:                  "
                + (DEGREE == CpModels.ANY_DEGREE ? "any (all graphs)" : DEGREE + " (regular)"));
        System.out.println("  Connectivity constraint: " + (CONNECTIVITY ? "ON" : "OFF"));
        System.out.println("  Print solutions:         " + (PRINT_SOLUTIONS ? "ON" : "OFF"));
        System.out.println("  Columns:                 " + String.join(", ", ALL_COLS));
        System.out.println();
    }

    /** Prints a section label above the rows. */
    private static void printGroupLabel(String title) {
        System.out.println();
        System.out.println("  ─── " + title + " ───");
    }

    /**
     * Two-line column header:
     * <pre>
     *   Line 1: individual column names
     *   Line 2: metric sub-label
     * </pre>
     */
    private static void printColumnHeader() {
        // Line 1 — column names
        StringBuilder r1 = new StringBuilder("|");
        r1.append(pad("Instance", W_INST)).append("|");
        for (String lbl : ALL_COLS) {
            r1.append(center(lbl, W_COL)).append("|");
        }
        System.out.println(r1);

        // Line 2 — metric sub-label
        StringBuilder r2 = new StringBuilder("|");
        r2.append(pad("", W_INST)).append("|");
        for (String ignored : ALL_COLS) {
            r2.append(center("Sol(s | memory)", W_COL)).append("|");
        }
        System.out.println(r2);
    }

    /** Prints a horizontal rule made of {@code ch}, with '+' at column borders. */
    private static void printHRule(char ch) {
        String seg = String.valueOf(ch).repeat(W_COL);
        StringBuilder sb = new StringBuilder("+");
        sb.append(String.valueOf(ch).repeat(W_INST)).append("+");
        for (String ignored : ALL_COLS) {
            sb.append(seg).append("+");
        }
        System.out.println(sb);
    }

    /**
     * Prints two sub-rows per instance, one cell per column ({@code null} prints "--").
     * <pre>
     *   Row 1: "N8" or "N8_d3" | solutions (time)
     *   Row 2: "  memory"      | peak memory used by the solver
     * </pre>
     */
    private static void printInstanceRows(String name, Result[] cols) {
        // Row 1: Solutions + Time
        StringBuilder r1 = new StringBuilder("|");
        r1.append(pad(name, W_INST)).append("|");
        for (Result r : cols) {
            r1.append(center(r == null ? "--" : solTime(r), W_COL)).append("|");
        }
        System.out.println(r1);

        // Row 2: peak memory
        StringBuilder r2 = new StringBuilder("|");
        r2.append(pad("  memory", W_INST)).append("|");
        for (Result r : cols) {
            r2.append(center(r == null ? "--" : memory(r.peakMemory), W_COL)).append("|");
        }
        System.out.println(r2);
    }

    /** Prints the closing banner. */
    private static void printFooter() {
        int w = tableWidth();
        System.out.println();
        System.out.println("=".repeat(w));
        System.out.println(center("End of Experiments", w));
        System.out.println("=".repeat(w));
        System.out.println();
    }

    // =========================================================================
    //  VALUE FORMATTERS
    // =========================================================================

    /** Formats "solutions (time in seconds)". */
    private static String solTime(Result r) {
        return String.format("%,d (%.2fs)", r.count, r.cpu / 1000.0);
    }

    /** Formats a byte count as KB/MB/GB; negative means "not collected". */
    private static String memory(long bytes) {
        if (bytes < 0) return "--";
        if (bytes >= 1L << 30) return String.format("%.2f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format("%.1f MB", bytes / (double) (1L << 20));
        return String.format("%.1f KB", bytes / 1024.0);
    }

    // =========================================================================
    //  LAYOUT HELPERS
    // =========================================================================

    /** Left-aligns {@code s} in a cell of {@code width}, truncating if needed. */
    private static String pad(String s, int width) {
        String cell = " " + s;
        if (cell.length() >= width) return cell.substring(0, width);
        return cell + " ".repeat(width - cell.length());
    }

    /** Centers {@code s} in a cell of {@code width}, truncating if needed. */
    private static String center(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        int total = width - s.length();
        int left = total / 2;
        int right = total - left;
        return " ".repeat(left) + s + " ".repeat(right);
    }

    /** Total table width in characters, including borders. */
    private static int tableWidth() {
        return 1 + W_INST + 1 + ALL_COLS.length * (W_COL + 1);
    }
}
