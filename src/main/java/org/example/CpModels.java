package org.example;

 import ilog.concert.IloException;
import ilog.concert.IloIntExpr;
import ilog.concert.IloIntVar;
import ilog.cp.IloCP;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
 import java.util.Arrays;


public class CpModels {

    // =========================================================================
    //  SOLVER PARAMETER CONSTANTS
    // =========================================================================

    /** Shared solver configuration applied to every method. */
    private static void configureSolver(IloCP cp) throws IloException {
        cp.setParameter(IloCP.IntParam.LogVerbosity,         IloCP.ParameterValues.Quiet);
        cp.setParameter(IloCP.IntParam.SearchType,           IloCP.ParameterValues.DepthFirst);
        cp.setParameter(IloCP.IntParam.DefaultInferenceLevel, IloCP.ParameterValues.Low);
        cp.setParameter(IloCP.IntParam.MemoryDisplay,        0);
    }

    // =========================================================================
    //  BASE-MODEL HELPERS  (shared by all methods)
    // =========================================================================

    /**
     * Creates the n×n binary adjacency-matrix variables and posts the three
     * structural constraints common to every configuration:
     * <ol>
     *   <li>Zero diagonal (no self-loops)</li>
     *   <li>Degree regularity</li>
     *   <li>Symmetry (undirected graph)</li>
     * </ol>
     *
     * @param cp     the CP solver instance
     * @param DEGREE degree sequence (all equal to d for a d-regular graph)
     * @return the matrix of decision variables
     */
    private static IloIntVar[][] buildBaseModelAdj(IloCP cp, int[] DEGREE)
            throws IloException {
        int N = DEGREE.length;
        IloIntVar[][] M = new IloIntVar[N][];
        for (int i = 0; i < N; i++) {
            M[i] = cp.intVarArray(N, 0, 1);
        }

        // 1. Zero diagonal
        for (int i = 0; i < N; i++) {
            cp.add(cp.eq(M[i][i], 0));
        }

        // 2. Degree regularity
        for (int i = 0; i < N; i++) {
           //cp.addEq(cp.sum(M[i]), DEGREE[i]); //Regular Graphs
            cp.addLe(cp.sum(M[i]), DEGREE[i]);//Bounded Graphs

        }

        // 3. Symmetry
        for (int i = 0; i < N; i++) {
            for (int j = i + 1; j < N; j++) {
                cp.add(cp.eq(M[i][j], M[j][i]));
            }
        }

        return M;
    }

    private static IloIntVar[][] buildBaseModelNei(IloCP cp, int[] DEGREE)
            throws IloException {

        int N = DEGREE.length;
        int D = DEGREE[0];   // uniform upper bound (see note for per-vertex bounds)

        IloIntVar[][] M = new IloIntVar[N][];

        for (int i = 0; i < N; i++) {

            // 0 = "no neighbor" padding, packed at the FRONT
           M[i] = cp.intVarArray(D, 0, N);          // was (D, 1, N)

            // no self loop (0 never equals i+1, so this is unchanged)
            for (int j = 0; j < D; j++)
                cp.add(cp.neq(M[i][j], i + 1));

            // ordered neighborhood: leading zeros, then strictly increasing reals
            for (int j = 0; j < D - 1; j++)
                cp.add(cp.or(                          // was a bare  cp.add(cp.lt(M[i][j], M[i][j + 1])); // REgular Graphs
                        cp.eq(M[i][j], 0),
                        cp.lt(M[i][j], M[i][j + 1])));
        }

        // undirected symmetry — unchanged (0 is never counted since u+1,v+1 >= 1)
        for (int u = 0; u < N; u++) {
            for (int v = u + 1; v < N; v++) {
                cp.add(cp.eq(
                        cp.count(M[u], v + 1),
                        cp.count(M[v], u + 1)));
            }
        }

        return M;
    }
    private static IloIntVar[][] buildBaseModelNei_used_with_regular_graphs(IloCP cp, int[] DEGREE)
            throws IloException {

        int N = DEGREE.length;
        int D = DEGREE[0];

        IloIntVar[][] M = new IloIntVar[N][];

        for (int i = 0; i < N; i++) {

            M[i] = cp.intVarArray(D, 1, N);

            // no self loop
            for (int j = 0; j < D; j++)
                cp.add(cp.neq(M[i][j], i + 1));

            // ordered neighborhood
            for (int j = 0; j < D - 1; j++)
                cp.add(cp.lt(M[i][j], M[i][j + 1]));
        }

        // undirected graph symmetry
        for (int u = 0; u < N; u++) {
            for (int v = u + 1; v < N; v++) {
                cp.add(
                        cp.eq(
                                cp.count(M[u], v + 1),
                                cp.count(M[v], u + 1)
                        )
                );
            }
        }

        return M;
    }

    // =========================================================================
    //  SYMMETRY-BREAKING HELPERS
    // =========================================================================

    /** Posts OptLex row-ordering constraints (optimized variant, ). */
    private static void addOptLex(IloCP cp, IloIntVar[][] M, int[] DEGREE)
            throws IloException {
        int N = M.length;
        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                //if (DEGREE[i] == DEGREE[j]) {
                    cp.add(cp.lexicographic(
                            arrayNew(M[i], i, j),
                            arrayNew(M[j], i, j)));
               // }
            }
        }
    }
    private static void addOptRevLex(IloCP cp, IloIntVar[][] M, int[] DEGREE)
            throws IloException {
        int N = M.length;
        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                cp.add(cp.lexicographic(
                        reverse(arrayNew(M[i], i, j)),
                        reverse(arrayNew(M[j], i, j))));
            }
        }


    }



    /** Neighbors model — OptLex, posted directly on expressions over M
     *  (no intermediary variables, no channeling constraints). */
    private static void addOptLexNei(IloCP cp, IloIntVar[][] M, int[] DEGREE)
            throws IloException {
        int N = DEGREE.length;

        // 0/1 EXPRESSIONS over the neighbor lists:  C[i][v] == 1  <=>  (v+1) ∈ M[i]
        IloIntExpr[][] C = new IloIntExpr[N][N];
        for (int i = 0; i < N; i++) {
            for (int v = 0; v < N; v++) {
                C[i][v] = (v == i) ? cp.constant(0)            // no self-loop
                        : cp.count(M[i], v + 1);
            }
        }

        // Identical shape to addOptLex — same column removal, same direction
        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                cp.add(cp.lexicographic(
                        arrayNew(C[i], i, j),
                        arrayNew(C[j], i, j)));
            }
        }
    }


    /** Neighbors model — OptRevLex ordering (this paper), expression-based, no aux vars. */
    private static void addOptRevLexNei(IloCP cp, IloIntVar[][] M, int[] DEGREE)
            throws IloException {
        int N = DEGREE.length;
        IloIntExpr[][] C = new IloIntExpr[N][N];
        for (int i = 0; i < N; i++)
            for (int v = 0; v < N; v++)
                C[i][v] = (v == i) ? cp.constant(0) : cp.count(M[i], v + 1);

        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                cp.add(cp.lexicographic(
                        reverse(arrayNew(C[i], i, j)),
                        reverse(arrayNew(C[j], i, j))));
            }
        }
    }

    // =========================================================================
    //  SOLUTION COLLECTION
    // =========================================================================

    /**
     * Runs the solver to exhaustion and returns a {@link Result}.
     * Solutions are counted but not written to disk (no I/O overhead in
     * benchmark mode). Pass {@code writer != null} to record matrices.
     */
    private static Result collectResults(IloCP cp, IloIntVar[][] M,
                                         PrintWriter writer)
            throws IloException {
        int N = M.length;
        int D = M[0].length;
        long start = System.currentTimeMillis();
        cp.startNewSearch();
        int count = 0;
        while (cp.next()) {
            count++;
            /* if (writer != null) {
                for (int i = 0; i < N; i++) {
                    for (int j = 0; j < D ; j++) {
                        writer.print(" " + (int) cp.getValue(M[i][j]));
                        System.out.print(" " + (int) cp.getValue(M[i][j]));
                    }
                    writer.println();
                    System.out.println();
                }
                 writer.println();
                 System.out.println();
            }*/
        }
        cp.endSearch();
        long elapsed = System.currentTimeMillis() - start;

        return new Result(
                count, elapsed,
                cp.getInfo(IloCP.IntInfo.NumberOfFails),
                cp.getInfo(IloCP.IntInfo.NumberOfBranches),
                cp.getInfo(IloCP.IntInfo.NumberOfChoicePoints),
                cp.getInfo(IloCP.IntInfo.NumberOfConstraints));
    }


    // =========================================================================
    //  GROUP A — SYMMETRY-BREAKING CONFIGURATIONS  (all graphs)
    // =========================================================================

    public static Result testOptLexAdj(int[] DEGREE) {
        try {
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelAdj(cp, DEGREE);
            addOptLex(cp, M, DEGREE);

            configureSolver(cp);
            try (PrintWriter w = new PrintWriter(new FileWriter("output_testOptimizedLex.txt"))) {
                return collectResults(cp, M, w);
            }
        } catch (IloException | IOException e) { throw new RuntimeException(e); }
    }

    public static Result testOptRevLexAdj(int[] DEGREE) {
        try {
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelAdj(cp, DEGREE);
            addOptRevLex(cp, M, DEGREE);

            configureSolver(cp);
            try (PrintWriter w = new PrintWriter(new FileWriter("output_testOptimizedLex.txt"))) {
                return collectResults(cp, M, w);
            }
        } catch (IloException | IOException e) { throw new RuntimeException(e); }
    }

    public static Result testOptRevLexAdjCon(int[] DEGREE) {
        try {
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelAdj(cp, DEGREE);
            addOptRevLex(cp, M, DEGREE);
            addOffDiagAdjConnectivity(cp, M, DEGREE);
            configureSolver(cp);
            try (PrintWriter w = new PrintWriter(new FileWriter("output_testOptimizedLex.txt"))) {
                return collectResults(cp, M, w);
            }
        } catch (IloException | IOException e) { throw new RuntimeException(e); }
    }



    public static Result testLexAdj(int[] DEGREE) {
        try {
            int N = DEGREE.length;
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelAdj(cp, DEGREE);

            for (int i = 0; i < N - 1; i++) {
               // for (int j = i + 1; j < N; j++) {
                //    if (DEGREE[i] == DEGREE[j]) {
                        cp.add(cp.lexicographic(M[i], M[i+1]));
                   // }
              //  }
            }

            configureSolver(cp);
            try (PrintWriter w = new PrintWriter(new FileWriter("output_testLex.txt"))) {
                return collectResults(cp, M, w);
            }
        } catch (IloException | IOException e) { throw new RuntimeException(e); }
    }

    public static Result testLexNei(int[] DEGREE) {
        try {
            int N = DEGREE.length;
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelNei(cp, DEGREE);

            for (int i = 0; i < N - 1; i++) {
                //for (int j = i + 1; j < N; j++) {
                //if (DEGREE[i] == DEGREE[j]) {
                cp.add(cp.lexicographic(M[i+1],M[i]));
                // }
                //}
            }

            configureSolver(cp);
            try (PrintWriter w = new PrintWriter(new FileWriter("output_testLex.txt"))) {
                return collectResults(cp, M, w);
            }
        } catch (IloException | IOException e) { throw new RuntimeException(e); }
    }

    public static Result testOptRevLexNeiCon(int[] DEGREE) {
        try {

            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelNei(cp, DEGREE);
            addOptRevLexNei(cp, M, DEGREE);
            addOffDiagNeiConnectivity(cp, M, DEGREE);

            configureSolver(cp);
            try (PrintWriter w = new PrintWriter(new FileWriter("output_testLex.txt"))) {
                return collectResults(cp, M, w);
            }
        } catch (IloException | IOException e) { throw new RuntimeException(e); }
    }

    public static Result testOptRevLexNei(int[] DEGREE) {
        try {

            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelNei(cp, DEGREE);
            addOptRevLexNei(cp, M, DEGREE);


            configureSolver(cp);
            try (PrintWriter w = new PrintWriter(new FileWriter("output_testLex.txt"))) {
                return collectResults(cp, M, w);
            }
        } catch (IloException | IOException e) { throw new RuntimeException(e); }
    }

    public static Result testOptLexNei(int[] DEGREE) {
        try {

            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelNei(cp, DEGREE);
            addOptLexNei(cp, M, DEGREE);
            //addConnectivityLower(cp, M, DEGREE); To Test Upper-Off Compatibility
            configureSolver(cp);
            try (PrintWriter w = new PrintWriter(new FileWriter("output_testLex.txt"))) {
                return collectResults(cp, M, w);
            }
        } catch (IloException | IOException e) { throw new RuntimeException(e); }
    }


    // ---------------------------------------------------------------------
//  (C5')  CONNECTIVITY — LOWER off-diagonal encoding, O(n)
//  Pair with OptLex ONLY (columns L→R). Do NOT pair with OptRevLex.
// ---------------------------------------------------------------------

    private static void addConnectivityLower(IloCP cp, IloIntVar[][] M, int[] DEGREE)
            throws IloException {
        int N = DEGREE.length;
        // adjacency form:  Σ_{j<i} A[i][j] > 0   for every vertex i > 0
        // neighbors form:  the SMALLEST neighbor of i is below i's own label.
        // Rows are sorted ⇒ smallest neighbor = FIRST slot ⇒ unary domain cut.
        for (int i = 1; i < N; i++) {
            cp.add(cp.lt(M[i][0], i + 1));   // 1-based values: neighbor label < i+1
        }
    }


    private static void addOffDiagAdjConnectivity(IloCP cp, IloIntVar[][] M, int[] DEGREE)
            throws IloException {
        int N = DEGREE.length;
        int D = DEGREE[0];
        for (int i = 0; i < N - 1; i++) {
            IloIntVar[] upper = Arrays.copyOfRange(M[i], i + 1, N);
            cp.add(cp.gt(cp.sum(upper), 0));
        }

    }

    private static void addOffDiagNeiConnectivity(IloCP cp, IloIntVar[][] M, int[] DEGREE)
            throws IloException {
        int N = DEGREE.length;
        int D = DEGREE[0];
        //con
        // Upper off-diagonal (Theorem 2), O(n): last (largest) neighbor of i exceeds i's label
        for (int i = 0; i < N - 1; i++) {
            cp.add(cp.gt(M[i][D - 1], i + 1));
        }


    }


    // =========================================================================
    //  ARRAY UTILITIES
    // =========================================================================


    /**
     * Returns the row array in <em>forward</em> order with columns
     * {@code exclude1} and {@code exclude2} removed.
     *
     * <p>Used by OptLex and the hybrid (when Lex is selected).
     */
    public static IloIntExpr[] arrayNew(IloIntExpr[] array,
                                        int exclude1, int exclude2) {
        if (array == null) throw new IllegalArgumentException("array must not be null");
        int len = array.length;
        if (exclude1 < 0 || exclude1 >= len || exclude2 < 0 || exclude2 >= len) {
            throw new IllegalArgumentException(
                    "Exclude indices must be in [0, " + len + ")");
        }
        int size = (exclude1 == exclude2) ? len - 1 : len - 2;
        IloIntExpr[] result = new IloIntExpr[size];
        int idx = 0;
        for (int i = 0; i < len; i++) {
            if (i != exclude1 && i != exclude2) {
                result[idx++] = array[i];
            }
        }
        return result;
    }

    /** Reverses an expression array (columns read right-to-left → RevLex). */
    private static IloIntExpr[] reverse(IloIntExpr[] a) {
        IloIntExpr[] r = new IloIntExpr[a.length];
        for (int k = 0; k < a.length; k++) r[k] = a[a.length - 1 - k];
        return r;
    }
}
