package org.example;

import ilog.concert.IloException;
import ilog.concert.IloIntExpr;
import ilog.concert.IloIntVar;
import ilog.cp.IloCP;

import java.util.Arrays;

/**
 * CP Optimizer models that enumerate the graphs on n vertices: all of them, or only the
 * d-regular ones when a degree d is fixed (see {@link #ANY_DEGREE}).
 *
 * <p>Two encodings of the same graph are compared:
 * <ul>
 *   <li><b>Adjacency model</b> ({@code *Adj}): an n×n 0/1 matrix, {@code M[i][j] = 1}
 *       iff vertices i and j are adjacent.</li>
 *   <li><b>Neighbors model</b> ({@code *Nei}): row i lists the neighbors of vertex i
 *       as 1-based labels in strictly increasing order (n×(n-1) with leading 0 padding
 *       for all graphs; n×d with no padding when the degree is fixed).</li>
 * </ul>
 *
 * <p>Each encoding is combined with OptRevLex symmetry breaking, and optionally
 * (a {@code connectivity} flag) with the upper off-diagonal connectivity constraint,
 * which restricts the search to connected graphs. Every public
 * {@code test*} method builds one configuration, enumerates all its solutions and
 * returns the search statistics.
 */
public class CpModels {

    /** How a solution encodes its graph: an n×n 0/1 matrix, or n rows of neighbor labels. */
    public enum Encoding { ADJACENCY, NEIGHBORS }

    /** Degree value meaning "no degree constraint": enumerate all graphs on n vertices. */
    public static final int ANY_DEGREE = -1;

    // =========================================================================
    //  SOLVER CONFIGURATION
    // =========================================================================

    /** Shared solver configuration applied to every model, so runs are comparable. */
    private static void configureSolver(IloCP cp) throws IloException {
        cp.setParameter(IloCP.IntParam.LogVerbosity,          IloCP.ParameterValues.Quiet);
        cp.setParameter(IloCP.IntParam.SearchType,            IloCP.ParameterValues.DepthFirst);
        cp.setParameter(IloCP.IntParam.DefaultInferenceLevel, IloCP.ParameterValues.Low);
        cp.setParameter(IloCP.IntParam.MemoryDisplay,         0);
    }

    // =========================================================================
    //  BASE MODELS
    // =========================================================================

    /**
     * Adjacency model: creates the n×n binary matrix and posts the structural
     * constraints of a simple undirected graph:
     * <ol>
     *   <li>Zero diagonal (no self-loops)</li>
     *   <li>Degree: every row sums to d (only when d is fixed)</li>
     *   <li>Symmetry: {@code M[i][j] == M[j][i]} (undirected graph)</li>
     * </ol>
     *
     * @param cp the CP solver instance
     * @param N  number of vertices
     * @param d  degree of every vertex, or {@link #ANY_DEGREE}
     * @return the n×n matrix of decision variables
     */
    private static IloIntVar[][] buildBaseModelAdj(IloCP cp, int N, int d)
            throws IloException {
        IloIntVar[][] M = new IloIntVar[N][];
        for (int i = 0; i < N; i++) {
            M[i] = cp.intVarArray(N, 0, 1);
        }

        // 1. Zero diagonal
        for (int i = 0; i < N; i++) {
            cp.add(cp.eq(M[i][i], 0));
        }

        // 2. Degree of every vertex (only when d is fixed)
        if (d != ANY_DEGREE) {
            for (int i = 0; i < N; i++) {
                cp.addEq(cp.sum(M[i]), d);
            }
        }

        // 3. Symmetry (only the upper triangle is needed)
        for (int i = 0; i < N; i++) {
            for (int j = i + 1; j < N; j++) {
                cp.add(cp.eq(M[i][j], M[j][i]));
            }
        }

        return M;
    }

    /**
     * Neighbors model: row i holds the neighbors of vertex i as 1-based labels in {1..n}.
     * <ul>
     *   <li><b>All graphs</b> ({@link #ANY_DEGREE}): every row has n-1 slots (the maximum
     *       degree); unused slots hold 0 and come first, e.g. {@code 0 0 2 5} = neighbors {2, 5}.</li>
     *   <li><b>d fixed</b>: every row has exactly d slots, all filled.</li>
     * </ul>
     * Posted constraints:
     * <ol>
     *   <li>No self-loop: vertex i (label i+1) never lists itself</li>
     *   <li>Ordered rows: neighbors are distinct and strictly increasing
     *       (after the leading zeros, for all graphs)</li>
     *   <li>Symmetry: v is a neighbor of u iff u is a neighbor of v</li>
     * </ol>
     *
     * @param cp the CP solver instance
     * @param N  number of vertices
     * @param d  degree of every vertex, or {@link #ANY_DEGREE}
     * @return the matrix of decision variables, n rows of n-1 (or d) slots
     */
    private static IloIntVar[][] buildBaseModelNei(IloCP cp, int N, int d)
            throws IloException {
        boolean regular = d != ANY_DEGREE;
        int D = regular ? d : N - 1;   // slots per row
        int min = regular ? 1 : 0;     // 0 = empty slot, only when the degree is free

        IloIntVar[][] M = new IloIntVar[N][];
        for (int i = 0; i < N; i++) {
            M[i] = cp.intVarArray(D, min, N);

            // 1. No self-loop
            for (int j = 0; j < D; j++) {
                cp.add(cp.neq(M[i][j], i + 1));
            }

            // 2. Ordered neighbor list
            for (int j = 0; j < D - 1; j++) {
                if (regular) {
                    // strictly increasing
                    cp.add(cp.lt(M[i][j], M[i][j + 1]));
                } else {
                    // a slot is empty (0), or the next slot holds a larger label,
                    // so zeros can only come before the neighbors, which are increasing
                    cp.add(cp.or(cp.eq(M[i][j], 0), cp.lt(M[i][j], M[i][j + 1])));
                }
            }
        }

        // 3. Symmetry: count(v in N(u)) == count(u in N(v)), each count being 0 or 1
        //    (padding zeros never match, since labels are >= 1)
        for (int u = 0; u < N; u++) {
            for (int v = u + 1; v < N; v++) {
                cp.add(cp.eq(
                        cp.count(M[u], v + 1),
                        cp.count(M[v], u + 1)));
            }
        }

        return M;
    }

    // =========================================================================
    //  SYMMETRY BREAKING  (OptRevLex)
    // =========================================================================

    /**
     * Adjacency model, OptRevLex: for every pair of rows i &lt; j, row i is
     * lexicographically ≤ row j when columns i and j are removed and the
     * remaining columns are read right-to-left (right-most column most significant).
     */
    private static void addOptRevLex(IloCP cp, IloIntVar[][] M) throws IloException {
        int N = M.length;
        if (N < 3) return;   // with columns i and j removed nothing is left to compare
        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                cp.add(cp.lexicographic(
                        reverse(arrayNew(M[i], i, j)),
                        reverse(arrayNew(M[j], i, j))));
            }
        }
    }

    /**
     * Neighbors model, OptRevLex: same ordering as {@link #addOptRevLex}, posted on
     * the 0/1 adjacency rows rebuilt as expressions over the neighbor lists
     * ({@code C[i][v] = count(M[i], v+1)}), with no extra variables or channeling.
     */
    private static void addOptRevLexNei(IloCP cp, IloIntVar[][] M, int N)
            throws IloException {
        // C[i][v] == 1  <=>  (v+1) is a neighbor of i
        IloIntExpr[][] C = new IloIntExpr[N][N];
        for (int i = 0; i < N; i++) {
            for (int v = 0; v < N; v++) {
                C[i][v] = (v == i) ? cp.constant(0) : cp.count(M[i], v + 1);
            }
        }

        if (N < 3) return;   // with columns i and j removed nothing is left to compare
        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                // Compare rows i and j with columns i and j excluded,
                // right-most column most significant (hence reverse).
                cp.add(cp.lexicographic(
                        reverse(arrayNew(C[i], i, j)),
                        reverse(arrayNew(C[j], i, j))));
            }
        }
    }

    // =========================================================================
    //  CONNECTIVITY  (upper off-diagonal)
    // =========================================================================

    /**
     * Adjacency model: every vertex i &lt; n-1 has at least one neighbor with a
     * larger label, i.e. the upper off-diagonal part of row i is not all zero.
     * Compatible with OptRevLex.
     */
    private static void addOffDiagAdjConnectivity(IloCP cp, IloIntVar[][] M)
            throws IloException {
        int N = M.length;
        for (int i = 0; i < N - 1; i++) {
            IloIntVar[] upper = Arrays.copyOfRange(M[i], i + 1, N);
            cp.add(cp.gt(cp.sum(upper), 0));
        }
    }

    /**
     * Neighbors model: same condition as {@link #addOffDiagAdjConnectivity}. Rows are
     * sorted (padding zeros first), so it reduces to "the last slot of row i, its largest
     * neighbor, is above i's label", a single unary constraint per vertex, O(n) in total.
     */
    private static void addOffDiagNeiConnectivity(IloCP cp, IloIntVar[][] M)
            throws IloException {
        int N = M.length;
        int D = M[0].length;
        for (int i = 0; i < N - 1; i++) {
            cp.add(cp.gt(M[i][D - 1], i + 1));
        }
    }

    // =========================================================================
    //  SOLUTION COUNTING
    // =========================================================================

    /**
     * Runs the configured solver to exhaustion and returns a {@link Result}
     * with the number of solutions, the wall-clock time in ms, the solver's
     * search statistics and its peak memory usage. Solutions are not stored.
     *
     * @param M        the decision variables, used only when printing
     * @param encoding how M encodes the graph, used only when printing
     * @param title    if not null, every solution is printed to the console under this title
     *                 (printing is included in the measured time)
     */
    private static Result collectResults(IloCP cp, IloIntVar[][] M, Encoding encoding, String title)
            throws IloException {
        if (title != null) {
            System.out.println();
            System.out.println("##### " + title + " #####");
        }
        long start = System.currentTimeMillis();
        cp.startNewSearch();
        int count = 0;
        while (cp.next()) {
            count++;
            if (title != null) {
                printSolution(cp, M, encoding, count);
            }
        }
        cp.endSearch();
        long elapsed = System.currentTimeMillis() - start;

        return new Result(
                count, elapsed,
                cp.getInfo(IloCP.IntInfo.NumberOfFails),
                cp.getInfo(IloCP.IntInfo.NumberOfBranches),
                cp.getInfo(IloCP.IntInfo.NumberOfChoicePoints),
                cp.getInfo(IloCP.IntInfo.NumberOfConstraints),
                cp.getInfo(IloCP.IntInfo.PeakMemoryUsage));
    }

    /**
     * Prints the current solution: the 0/1 rows of the adjacency matrix, or for the
     * neighbors model one line "vertex: neighbors" per vertex (labels are 1-based).
     */
    private static void printSolution(IloCP cp, IloIntVar[][] M, Encoding encoding, int index)
            throws IloException {
        StringBuilder sb = new StringBuilder("Solution ").append(index).append('\n');
        for (int i = 0; i < M.length; i++) {
            if (encoding == Encoding.NEIGHBORS) {
                sb.append(String.format("%3d:", i + 1));
            }
            for (IloIntVar v : M[i]) {
                sb.append(' ').append((int) cp.getValue(v));
            }
            sb.append('\n');
        }
        System.out.println(sb);
    }

    // =========================================================================
    //  BENCHMARK CONFIGURATIONS  (one table column each, see Main)
    // =========================================================================

    /**
     * Adjacency model + OptRevLex.
     *
     * @param n            number of vertices (n >= 2)
     * @param d            degree of every vertex, or {@link #ANY_DEGREE} for all graphs
     * @param connectivity if true, also posts the upper off-diagonal connectivity constraint
     * @param title        if not null, prints every solution under this title
     */
    public static Result testOptRevLexAdj(int n, int d, boolean connectivity, String title) {
        try {
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelAdj(cp, n, d);
            addOptRevLex(cp, M);
            if (connectivity) {
                addOffDiagAdjConnectivity(cp, M);
            }
            configureSolver(cp);
            return collectResults(cp, M, Encoding.ADJACENCY, title);
        } catch (IloException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Neighbors model + OptRevLex.
     *
     * @param n            number of vertices (n >= 2)
     * @param d            degree of every vertex (d >= 1), or {@link #ANY_DEGREE} for all graphs
     * @param connectivity if true, also posts the upper off-diagonal connectivity constraint
     * @param title        if not null, prints every solution under this title
     */
    public static Result testOptRevLexNei(int n, int d, boolean connectivity, String title) {
        try {
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelNei(cp, n, d);
            addOptRevLexNei(cp, M, n);
            if (connectivity) {
                addOffDiagNeiConnectivity(cp, M);
            }
            configureSolver(cp);
            return collectResults(cp, M, Encoding.NEIGHBORS, title);
        } catch (IloException e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    //  ARRAY UTILITIES
    // =========================================================================

    /**
     * Returns a copy of {@code array} in its original order with the entries at
     * {@code exclude1} and {@code exclude2} removed (both columns of the pair being
     * compared, as the OptRevLex ordering requires).
     */
    private static IloIntExpr[] arrayNew(IloIntExpr[] array, int exclude1, int exclude2) {
        int len = array.length;
        if (exclude1 < 0 || exclude1 >= len || exclude2 < 0 || exclude2 >= len) {
            throw new IllegalArgumentException("Exclude indices must be in [0, " + len + ")");
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

    /** Reverses an expression array, so lexicographic order reads columns right-to-left. */
    private static IloIntExpr[] reverse(IloIntExpr[] a) {
        IloIntExpr[] r = new IloIntExpr[a.length];
        for (int k = 0; k < a.length; k++) {
            r[k] = a[a.length - 1 - k];
        }
        return r;
    }
}
