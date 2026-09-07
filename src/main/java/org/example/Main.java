package org.example;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;



public class Main {


    // =========================================================================
    //  TABLE LAYOUT
    // =========================================================================

    /**
     * All 5 columns of the unified table.
     */
   /* private static final String[] ALL_COLS = {
            "LexAdj", "OptLexAdj",
            "LexNei", "OptLexNei", "OptRevLexNei", "OptRevLexNeiCon"
    };*/
    private static final String[] ALL_COLS = {
            "OptRevLexAdj",  "OptRevLexNei", "OptRevLexAdjCon", "OptRevLexNeiCon"
    };



    private static final int W_INST = 10;
    private static final int W_COL = 20;

    // =========================================================================
    //  ENTRY POINT
    // =========================================================================

    public static void main(String[] args) throws Exception {
        runPaperExperiments();
    }

    // =========================================================================
    //  EXPERIMENT RUNNER
    // =========================================================================

    public static void runPaperExperiments() throws IOException {

        printBanner();

        // Build instance lookup
        Map<String, TestCase> lookup = new LinkedHashMap<>();
        for (TestCase tc : PaperBenchmarkData.GRAPH_SAMPLES) {
            lookup.put(tc.name, tc);
        }

        // Degree-family groups — mirror paper table row groupings
        String[][] groups = {
              /*  {"3-Regular Graphs  K_n(3)  ",
                        "K6_3",   "K8_3",  "K10_3",  "K12_3",},*/
              /*  {"4-Regular Graphs  K_n(4)",
                        "K14_4", "K15_4" },*/

             /* {"5-Regular Graphs  K_n(5)",
                      "K14_5", "K16_5"},*/


             {"2-Regular Graphs  K_n(2)",
                        "K4_2","K5_2", "K6_2", "K7_2", "K8_2", "K9_2", "K10_2", "K11_2", "K12_2", "K13_2",
                     "K14_2", "K15_2", "K16_2"},// "K17_2", "K18_2" , "K19_2", "K20_2"},
             {"3-Regular Graphs  K_n(3)  ",
                   "K4_3", "K6_3", "K8_3","K10_3"},// "K12_3",  "K14_3"},
             {"4-Regular Graphs  K_n(4)",
                        "K7_4", "K8_4", "K9_4", "K10_4", "K11_4", "K12_4"},// "K13_4",  "K14_4"}, //"K7_4", "K8_4", "K9_4", "K10_4", "K11_4", "K12_4", "K13_4", "K14_4"},*/
             {"5-Regular Graphs  K_n(5)",
                      "K8_5", "K10_5"},// "K12_5"},  //,   "K14_5" },*/   // "K6_5", "K8_5", "K10_5", "K12_5"},
            /* {"6-Regular Graphs  K_n(6)",
                        "K6_6", "K7_6", "K8_6", "K9_6", "K10_6", "K11_6", "K14_6"},
             {"7-Regular Graphs  K_n(7)",
                        "K14_7", "K8_7", "K10_7", "K12_7", "K14_7"}*/
        };



        printColumnHeader();
        printHRule('=');

        for (String[] group : groups) {
            printGroupLabel(group[0]);

            for (int i = 1; i < group.length; i++) {
                TestCase tc = lookup.get(group[i]);
                if (tc == null) continue;

               // Result rOptRevLex_nei_con =   CpModels.testOptRevLexNeiCon(tc.degrees);


              //  Result rOptRevLex_nei_con =   CpModels.testOptRevLexAdjCon(tc.degrees);
                // ---- Symmetry breaking (Adjacency Model) ----
                //Result rLex_adj = null;// CpModels.testLexAdj(tc.degrees);
               // Result rOptLex_adj =  rOptRevLex_nei_con  ;//   CpModels.testOptLexAdj(tc.degrees);
                Result rOptRevLex_adj =     CpModels.testOptRevLexAdj(tc.degrees);
                Result rOptRevLex_adj_con =  CpModels.testOptRevLexAdjCon(tc.degrees);

                // ---- Symmetry breaking (Neighbors Model) ----
                //Result rLex_nei =   null;//  CpModels.testLexNei(tc.degrees);
               // Result rOptLex_nei =   CpModels.testOptLexNei(tc.degrees);

                //System.out.println("testOptRevLexNei started ");
                Result rOptRevLex_nei =     CpModels.testOptRevLexNei(tc.degrees);

                //System.out.println("testOptRevLexNei ended ");

                   Result rOptRevLex_nei_con =   CpModels.testOptRevLexNeiCon(tc.degrees);

                printInstanceRows(tc.name,
                        new Result[]{rOptRevLex_adj, rOptRevLex_nei, rOptRevLex_adj_con, rOptRevLex_nei_con});
                printHRule('-');
            }
        }

        printFooter();
    }

    // =========================================================================
    //  TABLE PRINTING
    // =========================================================================

    private static void printBanner() {
        int w = tableWidth();
        System.out.println();
        System.out.println("=".repeat(w));
        System.out.println(center("d-Regular Graph Generation — Benchmark", w));
        System.out.println(center(
                "OptLex / OptRevLex  ×  Symmetry-breaking & Connectivity", w));
        System.out.println("=".repeat(w));
        System.out.println();
        System.out.println("Metrics:  Row 1 = Sol (CPU time s)   |   Row 2 = Failures/Branches/ChoicePoints");
        System.out.println("(>60000s) = time-limit exceeded        |   -- = not collected");
        System.out.println();
        System.out.println("  Columns:");
        System.out.println();
    }

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
            r2.append(center("Sol(s) | f/br/ch", W_COL)).append("|");
        }
        System.out.println(r2);
    }

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
     * Prints two sub-rows per instance across all 5 columns.
     * <pre>
     *   Row 1: instance name | sol (time) × 5
     *   Row 2: "  f/br/ch"  | f/br/ch    × 5
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
        r2.append(pad("  f/br/ch", W_INST)).append("|");
        for (Result r : cols) {
            r2.append(center(r == null ? "--" : fbch(r), W_COL)).append("|");
        }
        System.out.println(r2);
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
    //  VALUE FORMATTERS
    // =========================================================================

    private static String solTime(Result r) {
        String t = (r.cpu >= 600000_000)
                ? "(>6000000s)"
                : String.format("%.2fs", r.cpu / 1000.0);
        return String.format("%,d (%s)", r.count, t);
    }

    private static String fbch(Result r) {
        return metric(r.fails) + "/" + metric(r.branches) + "/" + metric(r.choicePoints);
    }

    private static String metric(long v) {
        if (v < 0) return "--";
        if (v >= 1_000_000L) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000L) return String.format("%.1fK", v / 1_000.0);
        return Long.toString(v);
    }

    // =========================================================================
    //  LAYOUT HELPERS
    // =========================================================================

    private static String pad(String s, int width) {
        String cell = " " + s;
        if (cell.length() >= width) return cell.substring(0, width);
        return cell + " ".repeat(width - cell.length());
    }

    private static String center(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        int total = width - s.length();
        int left = total / 2;
        int right = total - left;
        return " ".repeat(left) + s + " ".repeat(right);
    }

    private static int tableWidth() {
        return 1 + W_INST + 1 + ALL_COLS.length * (W_COL + 1);
    }
}
