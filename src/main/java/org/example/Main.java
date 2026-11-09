package org.example;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Benchmark driver: runs every configuration of {@link CpModels} on a selection of
 * d-regular instances from {@link PaperBenchmarkData} and prints the results as a
 * text table (one column per configuration, two lines per instance).
 */
public class Main {

    // =========================================================================
    //  TABLE LAYOUT
    // =========================================================================

    // =========================================================================
    //  CONFIGURATION
    // =========================================================================

    /**
     * true  = also post the upper off-diagonal connectivity constraint (connected graphs only);
     * false = OptRevLex symmetry breaking only (all graphs, connected or not).
     */
    private static final boolean CONNECTIVITY = true;

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
        runPaperExperiments();
    }

    // =========================================================================
    //  EXPERIMENT RUNNER
    // =========================================================================

    /** Runs all configurations on each selected instance and prints the table. */
    public static void runPaperExperiments() {
        printBanner();

        // Instance name -> test case
        Map<String, TestCase> lookup = new LinkedHashMap<>();
        for (TestCase tc : PaperBenchmarkData.GRAPH_SAMPLES) {
            lookup.put(tc.name, tc);
        }

        // Instances to run, grouped by degree. The first entry of each group is its label;
        // the other entries are instance names from PaperBenchmarkData.
        String[][] groups = {
                {"3-Regular Graphs  K_n(3)", "K8_3"},//, "K12_3", "K14_3"},
                {"4-Regular Graphs  K_n(4)", "K6_4"},//, "K13_4", "K14_4"},
                {"5-Regular Graphs  K_n(5)", "K8_5"},//,"K10_5", "K12_5"},
        };

        printColumnHeader();
        printHRule('=');

        for (String[] group : groups) {
            printGroupLabel(group[0]);

            for (int i = 1; i < group.length; i++) {
                TestCase tc = lookup.get(group[i]);
                if (tc == null) continue;   // unknown instance name: skip it

                // Title shown above the printed solutions; null disables printing.
                Result rAdj = CpModels.testOptRevLexAdj(tc.degrees, CONNECTIVITY,
                        PRINT_SOLUTIONS ? tc.name + " " + ALL_COLS[0] : null);
                Result rNei = CpModels.testOptRevLexNei(tc.degrees, CONNECTIVITY,
                        PRINT_SOLUTIONS ? tc.name + " " + ALL_COLS[1] : null);

                printInstanceRows(tc.name, new Result[]{rAdj, rNei});
                printHRule('-');
            }
        }

        printFooter();
    }

    // =========================================================================
    //  TABLE PRINTING
    // =========================================================================

    /** Prints the title and the legend explaining the cell contents. */
    private static void printBanner() {
        int w = tableWidth();
        System.out.println();
        System.out.println("=".repeat(w));
        System.out.println(center("d-Regular Graph Generation — Benchmark", w));
        System.out.println(center(
                "OptLex / OptRevLex  ×  Symmetry-breaking & Connectivity", w));
        System.out.println("=".repeat(w));
        System.out.println();
        System.out.println("Metrics:  Row 1 = Sol (CPU time s)   |   Row 2 = Peak memory");
        System.out.println("(>60000s) = time-limit exceeded        |   -- = not collected");
        System.out.println();
        System.out.println("  Connectivity constraint: " + (CONNECTIVITY ? "ON" : "OFF"));
        System.out.println("  Print solutions:         " + (PRINT_SOLUTIONS ? "ON" : "OFF"));
        System.out.println("  Columns: " + String.join(", ", ALL_COLS));
        System.out.println();
    }

    /** Prints the label of a degree group above its rows. */
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
     *   Row 1: instance name | solutions (time)
     *   Row 2: "  memory"    | peak memory used by the solver
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

        // Row 2: f / br / ch
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
        String t = (r.cpu >= 600000_000)
                ? "(>6000000s)"
                : String.format("%.2fs", r.cpu / 1000.0);
        return String.format("%,d (%s)", r.count, t);
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
