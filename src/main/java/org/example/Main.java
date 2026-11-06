package org.example;

import org.example.CpModels.Connectivity;
import org.example.CpModels.Encoding;
import org.example.CpModels.SolutionListener;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every benchmark instance under each model configuration and prints a
 * results table. Each instance takes one line, with one cell per configuration:
 * <pre>
 *   solutions (time | peak memory used by the solver)
 * </pre>
 *
 * <p>Optionally, the graphs found are also saved, one file per instance and configuration
 * (e.g. {@code N8_OptRevLexAdj.txt}). Command-line options override the defaults below:
 * <pre>
 *   --txt / --no-txt       save graphs as .txt (adjacency matrices or neighbor lists)
 *   --pdf / --no-pdf       draw graphs in a .pdf
 *   --limit-graphs=N       write at most the first N graphs of each run
 *   --out=DIR              parent output folder; each run writes to DIR/<date_time>/
 * </pre>
 */
public class Main {

    /** One column of the results table: an encoding and its connectivity constraints. */
    private record Config(String label, Encoding encoding, Connectivity connectivity) {}

    private static final List<Config> CONFIGS = List.of(
            new Config("OptRevLexAdj",    Encoding.ADJACENCY, Connectivity.NONE),
            new Config("OptRevLexNei",    Encoding.NEIGHBORS, Connectivity.NONE),
            new Config("OptRevLexAdjCon", Encoding.ADJACENCY, Connectivity.UPPER_OFF_DIAGONAL),
            new Config("OptRevLexNeiCon", Encoding.NEIGHBORS, Connectivity.UPPER_OFF_DIAGONAL),
            new Config("OptRevLexNeiLastValidSlotCon", Encoding.NEIGHBORS, Connectivity.LAST_VALID_SLOT)
    );

    // ---- Output defaults (overridable from the command line) ----
    private static final boolean SAVE_TXT = false;
    private static final boolean SAVE_PDF = false;
    /** Output-only cap: the first LIMIT_GRAPHS graphs of each run are written, to bound memory. */
    private static final int LIMIT_GRAPHS = 10;
    private static final String OUTPUT_DIR = "results";

    /** What to save, where, and how many graphs per run at most. */
    private record Output(boolean txt, boolean pdf, int limitGraphs, Path dir) {

        boolean enabled() {
            return txt || pdf;
        }

        static Output fromArgs(String[] args) {
            boolean txt = SAVE_TXT, pdf = SAVE_PDF;
            int limitGraphs = LIMIT_GRAPHS;
            String dir = OUTPUT_DIR;
            for (String arg : args) {
                if (arg.equals("--txt")) txt = true;
                else if (arg.equals("--no-txt")) txt = false;
                else if (arg.equals("--pdf")) pdf = true;
                else if (arg.equals("--no-pdf")) pdf = false;
                else if (arg.startsWith("--limit-graphs=")) limitGraphs = Integer.parseInt(arg.substring(15));
                else if (arg.startsWith("--out=")) dir = arg.substring(6);
                else throw new IllegalArgumentException("Unknown option: " + arg);
            }
            return new Output(txt, pdf, limitGraphs, Path.of(dir).resolve(runFolderName()));
        }

        /** Folder name for this run, from the start time, e.g. {@code 2026-11-06_14-30-05}. */
        private static String runFolderName() {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        }
    }

    private static final int NAME_WIDTH = 10;
    /** Wide enough for every label, with a space on each side. */
    private static final int COL_WIDTH = Math.max(32,
            CONFIGS.stream().mapToInt(c -> c.label().length() + 2).max().orElse(0));

    public static void main(String[] args) {
        Output output = Output.fromArgs(args);

        printBanner();
        printHeader();
        printRule('=');

        for (PaperBenchmarkData.Group group : PaperBenchmarkData.GROUPS) {
            System.out.println();
            System.out.println("  ─── " + group.title() + " ───");

            for (TestCase tc : group.instances()) {
                List<Result> results = new ArrayList<>();
                for (Config c : CONFIGS) {
                    results.add(run(tc, c, output));
                }
                printInstance(tc.name(), results);
                printRule('-');
            }
        }

        if (output.enabled()) {
            System.out.println();
            System.out.printf("Graphs saved to %s/ (%s%s%s), at most the first %,d per run.%n",
                    output.dir(), output.txt() ? ".txt" : "", output.txt() && output.pdf() ? " + " : "",
                    output.pdf() ? ".pdf" : "", output.limitGraphs());
        }
        printFooter();
    }

    /**
     * Solves one instance under one configuration. When saving is on, the first
     * {@code limitGraphs} graphs are kept and written once the search ends;
     * the search itself always runs to completion.
     */
    private static Result run(TestCase tc, Config c, Output output) {
        if (!output.enabled()) {
            return CpModels.solve(tc.vertices(), c.encoding(), c.connectivity(), null);
        }

        List<int[][]> kept = new ArrayList<>();
        SolutionListener keepFirst = values -> {
            if (kept.size() < output.limitGraphs()) kept.add(values.get());
        };
        Result r = CpModels.solve(tc.vertices(), c.encoding(), c.connectivity(), keepFirst);

        String title = tc.name() + "_" + c.label();
        if (output.txt()) GraphFiles.writeTxt(output.dir(), title, tc, c.encoding(), r.count(), kept);
        if (output.pdf()) GraphFiles.writePdf(output.dir(), title, tc, c.encoding(), r.count(), kept);
        return r;
    }

    // =========================================================================
    //  TABLE PRINTING
    // =========================================================================

    private static void printBanner() {
        int w = tableWidth();
        System.out.println();
        System.out.println("=".repeat(w));
        System.out.println(center("All Graphs on N Vertices — Benchmark", w));
        System.out.println(center("OptRevLex  ×  Adjacency / Neighbors  ×  Connectivity", w));
        System.out.println("=".repeat(w));
        System.out.println();
        System.out.println("Each cell = solutions (time | peak memory)");
        System.out.println();
    }

    private static void printHeader() {
        printRow("Instance", CONFIGS.stream().map(Config::label).toList());
        printRow("", CONFIGS.stream().map(c -> "Sol (s | memory)").toList());
    }

    private static void printInstance(String name, List<Result> results) {
        printRow(name, results.stream().map(Main::cell).toList());
    }

    private static void printRow(String name, List<String> cells) {
        StringBuilder sb = new StringBuilder("|").append(padRight(name, NAME_WIDTH)).append("|");
        for (String cell : cells) {
            sb.append(center(cell, COL_WIDTH)).append("|");
        }
        System.out.println(sb);
    }

    private static void printRule(char ch) {
        String c = String.valueOf(ch);
        StringBuilder sb = new StringBuilder("+").append(c.repeat(NAME_WIDTH)).append("+");
        for (int i = 0; i < CONFIGS.size(); i++) {
            sb.append(c.repeat(COL_WIDTH)).append("+");
        }
        System.out.println(sb);
    }

    private static void printFooter() {
        int w = tableWidth();
        System.out.println();
        System.out.println("=".repeat(w));
        System.out.println(center("End of Experiments", w));
        System.out.println("=".repeat(w));
        System.out.println();
    }

    // =========================================================================
    //  FORMATTING
    // =========================================================================

    /** One result cell, e.g. "216 (0.41s | 2.6 MB)". */
    private static String cell(Result r) {
        return String.format("%,d (%.2fs | %s)", r.count(), r.timeMs() / 1000.0, memory(r));
    }

    /** Bytes as "512 KB", "12.3 MB" or "1.25 GB". */
    private static String memory(Result r) {
        double kb = r.memoryBytes() / 1024.0;
        if (kb < 1024) return String.format("%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    private static String padRight(String s, int width) {
        String cell = " " + s;
        return cell.length() >= width ? cell.substring(0, width) : cell + " ".repeat(width - cell.length());
    }

    private static String center(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        int left = (width - s.length()) / 2;
        return " ".repeat(left) + s + " ".repeat(width - s.length() - left);
    }

    private static int tableWidth() {
        return 1 + NAME_WIDTH + 1 + CONFIGS.size() * (COL_WIDTH + 1);
    }
}
