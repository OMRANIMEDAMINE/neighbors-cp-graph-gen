package org.example;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntExpr;
import ilog.concert.IloIntVar;
import ilog.cp.IloCP;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;


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
            cp.addEq(cp.sum(M[i]), DEGREE[i]);
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

    /** Posts OptLex row-ordering constraints (optimized variant, Codish 2018). */
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
            if (writer != null) {
                for (int i = 0; i < N; i++) {
                    for (int j = 0; j < D ; j++) {
                        writer.print(" " + (int) cp.getValue(M[i][j]));
                    }
                    writer.println();
                }
                writer.println();
            }
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

    /** All graphs — OptLex ordering (Codish 2018). */
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



    // =========================================================================
    //  LEGACY METHODS  (kept for backward-compatibility; not used in paper table)
    // =========================================================================

    /** Non-optimized Lex (baseline). */
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


    public static Result testOptLexNei(int[] DEGREE) {
        try {
            int N = DEGREE.length;
            int D = DEGREE[0];
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelNei(cp, DEGREE);


            for (int i = 0; i < N - 1; i++) {
                for (int j = i + 1; j < D; j++) {

                    List<IloIntVar> filteredA = new ArrayList<>();
                    List<IloIntVar> filteredB = new ArrayList<>();
                    for (int k = 0; k < N; k++) {
                        // skip position k if it structurally carries the edge (i,j):
                        // value j+1 is excluded from row i's domain  (no self-loop, sorted)
                        // value i+1 is excluded from row j's domain
                        // so we detect superposition by checking which slot holds that value
                        // using getLB()==getUB() won't work at build time either,
                        // so we use the STRUCTURAL fact: in sorted row i, j+1 appears at
                        // exactly one fixed position we can compute: skip k if k==i or k==j
                        if (k == (M[i]) || k == (M[j])) continue;

                        filteredA.add(M[i][k]);
                        filteredB.add(M[j][k]);
                    }

                    if (filteredA.size() < 2) continue;

                    cp.add(cp.lexicographic(
                            filteredA.toArray(new IloIntVar[0]),
                            filteredB.toArray(new IloIntVar[0])));
                }
            }


                    //if (DEGREE[i] == DEGREE[j]) {
                  /*  cp.add(cp.lexicographic(
                            arrayNewNei(M[j], i, j),
                            arrayNewNei(M[i], i, j)));
                    // }*/




            configureSolver(cp);
            try (PrintWriter w = new PrintWriter(new FileWriter("output_testLex.txt"))) {
                return collectResults(cp, M, w);
            }
        } catch (IloException | IOException e) { throw new RuntimeException(e); }
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


}
