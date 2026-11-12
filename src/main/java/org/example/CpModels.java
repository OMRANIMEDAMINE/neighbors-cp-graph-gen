package org.example;

import ilog.concert.IloException;
import ilog.concert.IloIntExpr;
import ilog.concert.IloIntVar;
import ilog.cp.IloCP;

/**
 * CP Optimizer models that enumerate, up to isomorphism, the graphs with a given degree
 * sequence {@code deg} (vertex i has degree deg[i], n = deg.length, increasing order).
 *
 * <p>Two encodings of a graph:
 * <ul>
 *   <li><b>Adjacency</b>: an n×n 0/1 matrix, {@code M[i][j] = 1} iff i and j are adjacent.</li>
 *   <li><b>Neighbors</b>: an n×D matrix (D = largest degree); row i lists the neighbors of
 *       vertex i as labels 1..n, empty slots hold 0. The neighbors are listed increasing,
 *       after the zeros for OptLex ({@code 0 0 2 5}), before them for OptRevLex
 *       ({@code 2 5 0 0}).</li>
 * </ul>
 *
 * <p>The file has three layers:
 * <ol>
 *   <li><b>Models</b>: one public method per configuration (one table column). Each one
 *       builds a base model, posts its constraints and solves.</li>
 *   <li><b>Base models</b>: the variables of a graph with the given degrees.</li>
 *   <li><b>Constraints</b>: one method per symmetry-breaking or connectivity constraint.</li>
 * </ol>
 */
public class CpModels {

    // =========================================================================
    //  MODELS  (one per table column)
    // =========================================================================

    /** Adjacency matrix + OptLex. */
    public static Result optLexAdj(int[] deg, String title) throws IloException {
        IloCP cp = new IloCP();
        IloIntVar[][] M = buildAdjacencyModel(cp, deg);
        addOptLex(cp, M, deg);
        return solve(cp, M, false, title);
    }

    /** Neighbor lists + OptLex on the 0/1 rows rebuilt from the lists. */
    public static Result optLexNei(int[] deg, String title) throws IloException {
        IloCP cp = new IloCP();
        IloIntVar[][] M = buildNeighborsModel(cp, deg);
        addLexRows(cp, M, deg);
        addOptLexNei(cp, M, deg);
        return solve(cp, M, true, title);
    }

    /** Neighbor lists + OptLex posted directly on the lists ({@link #addOptLexNeiDir}). */
    public static Result optLexNeiDir(int[] deg, String title) throws IloException {
        IloCP cp = new IloCP();
        IloIntVar[][] M = buildNeighborsModel(cp, deg);
        addLexRows(cp, M, deg);
        addOptLexNeiDir(cp, M, deg);
        return solve(cp, M, true, title);
    }

    /** Adjacency matrix + OptRevLex, optionally connected graphs only. */
    public static Result optRevLexAdj(int[] deg, boolean connectivity, String title)
            throws IloException {
        IloCP cp = new IloCP();
        IloIntVar[][] M = buildAdjacencyModel(cp, deg);
        addOptRevLex(cp, M, deg);
        if (connectivity) addUpperOffDiagonal(cp, M);
        return solve(cp, M, false, title);
    }

    /** Neighbor lists + OptRevLex on the rebuilt 0/1 rows, optionally connected graphs only. */
    public static Result optRevLexNei(int[] deg, boolean connectivity, String title)
            throws IloException {
        IloCP cp = new IloCP();
        IloIntVar[][] M = buildNeighborsModel(cp, deg);
        addRevLexRows(cp, M, deg);
        addOptRevLexNei(cp, M, deg);
        if (connectivity) addLargestNeighborAbove(cp, M, deg);
        return solve(cp, M, true, title);
    }

    /**
     * Neighbor lists + OptRevLex posted directly on the lists ({@link #addOptRevLexNeiDir}),
     * optionally connected graphs only.
     */
    public static Result optRevLexNeiDir(int[] deg, boolean connectivity, String title)
            throws IloException {
        IloCP cp = new IloCP();
        IloIntVar[][] M = buildNeighborsModel(cp, deg);
        addRevLexRows(cp, M, deg);
        addOptRevLexNeiDir(cp, M, deg);
        if (connectivity) addLargestNeighborAbove(cp, M, deg);
        return solve(cp, M, true, title);
    }

    // =========================================================================
    //  BASE MODELS
    // =========================================================================

    /** n×n 0/1 matrix: zero diagonal, row i sums to deg[i], symmetric. */
    private static IloIntVar[][] buildAdjacencyModel(IloCP cp, int[] deg) throws IloException {
        int n = deg.length;
        IloIntVar[][] M = new IloIntVar[n][];
        for (int i = 0; i < n; i++) {
            M[i] = cp.intVarArray(n, 0, 1);
            cp.add(cp.eq(M[i][i], 0));              // no self-loop
            cp.addEq(cp.sum(M[i]), deg[i]);         // degree
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                cp.add(cp.eq(M[i][j], M[j][i]));    // undirected
            }
        }
        return M;
    }

    /**
     * n×D matrix of neighbor labels 1..n (D = largest degree, 0 = empty slot): no
     * self-loop, and v is a neighbor of u iff u is a neighbor of v. Where the zeros sit and
     * in which order the neighbors are listed is the row layout, posted right after by
     * {@link #addLexRows} or {@link #addRevLexRows}; the layout also fixes the degree and
     * forbids a repeated neighbor (simple graph).
     */
    private static IloIntVar[][] buildNeighborsModel(IloCP cp, int[] deg) throws IloException {
        int n = deg.length;
        int D = 1;
        for (int d : deg) D = Math.max(D, d);

        IloIntVar[][] M = new IloIntVar[n][];
        for (int i = 0; i < n; i++) {
            M[i] = cp.intVarArray(D, 0, n);
            for (int k = 0; k < D; k++) cp.add(cp.neq(M[i][k], i + 1));   // no self-loop
        }
        for (int u = 0; u < n; u++) {
            for (int v = u + 1; v < n; v++) {
                cp.add(cp.eq(cp.count(M[u], v + 1), cp.count(M[v], u + 1)));   // undirected
            }
        }
        return M;
    }

    // =========================================================================
    //  CONSTRAINTS: row layout of the neighbor lists (simple graph)
    //  The zeros are fixed by position and the neighbors strictly ordered, so row i
    //  holds exactly deg[i] distinct neighbors: no degree count, no all-different.
    // =========================================================================

    /** OptLex layout: slots 0..D-deg[i]-1 are 0, then the neighbors strictly increasing: {@code 0 0 2 5}. */
    private static void addLexRows(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        for (int i = 0; i < M.length; i++) {
            int D = M[i].length, first = D - deg[i];   // first neighbor slot
            for (int k = 0; k < first; k++) cp.add(cp.eq(M[i][k], 0));
            if (deg[i] > 0) cp.add(cp.ge(M[i][first], 1));
            for (int k = first; k + 1 < D; k++) cp.add(cp.lt(M[i][k], M[i][k + 1]));
        }
    }

    /** OptRevLex layout: the neighbors strictly increasing in slots 0..deg[i]-1, then 0: {@code 2 5 0 0}. */
    private static void addRevLexRows(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        for (int i = 0; i < M.length; i++) {
            int D = M[i].length, last = deg[i] - 1;    // last neighbor slot
            for (int k = deg[i]; k < D; k++) cp.add(cp.eq(M[i][k], 0));
            if (deg[i] > 0) cp.add(cp.ge(M[i][0], 1));
            for (int k = 0; k < last; k++) cp.add(cp.lt(M[i][k], M[i][k + 1]));
        }
    }

    // =========================================================================
    //  CONSTRAINTS: symmetry breaking
    //  Posted only for pairs i < j with deg[i] = deg[j]: only vertices of equal
    //  degree can be swapped, comparing other pairs would lose graphs.
    // =========================================================================

    /** Adjacency, OptLex: row i ≤lex row j, columns i and j removed, read left to right. */
    private static void addOptLex(IloCP cp, IloIntExpr[][] A, int[] deg) throws IloException {
        int n = deg.length;
        if (n < 3) return;   // nothing left once columns i and j are removed
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (deg[i] != deg[j]) continue;
                cp.add(cp.lexicographic(without(A[i], i, j), without(A[j], i, j)));
            }
        }
    }

    /** Adjacency, OptRevLex: as {@link #addOptLex}, rows read right to left. */
    private static void addOptRevLex(IloCP cp, IloIntExpr[][] A, int[] deg) throws IloException {
        int n = deg.length;
        if (n < 3) return;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (deg[i] != deg[j]) continue;
                cp.add(cp.lexicographic(reverse(without(A[i], i, j)), reverse(without(A[j], i, j))));
            }
        }
    }

    /** Neighbors, OptLex: {@link #addOptLex} on the 0/1 rows rebuilt from the lists. */
    private static void addOptLexNei(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        addOptLex(cp, adjacencyOf(cp, M), deg);
    }

    /** Neighbors, OptRevLex: {@link #addOptRevLex} on the 0/1 rows rebuilt from the lists. */
    private static void addOptRevLexNei(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        addOptRevLex(cp, adjacencyOf(cp, M), deg);
    }

    /**
     * Neighbors, OptRevLex posted directly on the neighbor lists, read from the largest
     * neighbor down (the neighbor slots reversed): the same order as
     * {@link #addOptRevLexNei}, without rebuilding the 0/1 rows. For every pair i &lt; j with
     * deg[i] = deg[j], with e = 1 iff i and j are adjacent:
     * <pre>
     *   list(i) minus label j+1  ≤lex  list(j) minus label i+1
     * </pre>
     * Read right to left, the 0/1 rows first differ at the LARGEST vertex w of
     * N(i)\{j} Δ N(j)\{i}, and row i ≤ row j iff w ∈ N(j). In the decreasing lists the first
     * difference is also at w, and the list holding w is the larger one.
     */
    private static void addOptRevLexNeiDir(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        int n = deg.length;
        if (n < 3) return;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (deg[i] != deg[j] || deg[i] == 0) continue;
                IloIntExpr e = cp.count(M[i], j + 1);   // = count(M[j], i+1), by symmetry
                // decreasing lists: the increasing neighbor slots 0..deg-1, reversed
                IloIntExpr[] li = reverse(slots(M[i], 0, deg[i])), lj = reverse(slots(M[j], 0, deg[j]));
                cp.add(cp.lexicographic(dropLabel(cp, li, j + 1, e), dropLabel(cp, lj, i + 1, e)));
            }
        }
    }

    /**
     * Neighbors, OptLex posted directly on the increasing lists: the same order as
     * {@link #addOptLexNei}. For every pair i &lt; j with deg[i] = deg[j], with e = 1 iff i
     * and j are adjacent:
     * <pre>
     *   list(j) minus label i+1  ≤lex  list(i) minus label j+1     (arguments swapped)
     * </pre>
     * Read left to right, the 0/1 rows first differ at the SMALLEST vertex w of
     * N(i)\{j} Δ N(j)\{i}, and row i ≤ row j iff w ∈ N(j). In the increasing lists the first
     * difference is also at w, and the list holding w is the smaller one.
     */
    private static void addOptLexNeiDir(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        int n = deg.length;
        if (n < 3) return;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (deg[i] != deg[j] || deg[i] == 0) continue;
                IloIntExpr e = cp.count(M[i], j + 1);
                int D = M[i].length;
                IloIntExpr[] li = slots(M[i], D - deg[i], D), lj = slots(M[j], D - deg[j], D);
                cp.add(cp.lexicographic(dropLabelAsc(cp, lj, i + 1, e, n), dropLabelAsc(cp, li, j + 1, e, n)));
            }
        }
    }

    /**
     * A decreasing neighbor list with {@code label} deleted only if e = 1 (label adjacent):
     * every entry at or after the label moves one slot left, and a 0 enters at the end.
     * <pre>
     *   out[k] = L[k] + s_k · (L[k+1] − L[k]),   s_k = e · [L[k] ≤ label],   L[d] = 0
     * </pre>
     */
    private static IloIntExpr[] dropLabel(IloCP cp, IloIntExpr[] L, int label, IloIntExpr e)
            throws IloException {
        int d = L.length;
        IloIntExpr[] out = new IloIntExpr[d];
        for (int k = 0; k < d; k++) {
            IloIntExpr shift = cp.prod(e, cp.le(L[k], label));
            IloIntExpr next = (k + 1 < d) ? L[k + 1] : cp.constant(0);
            out[k] = cp.sum(L[k], cp.prod(shift, cp.diff(next, L[k])));
        }
        return out;
    }

    /**
     * An increasing neighbor list with {@code label} deleted only if e = 1: as
     * {@link #dropLabel}, with s_k = e · [L[k] ≥ label] and the sentinel n+1 entering at
     * the end (it is larger than every label, as 0 is smaller in the decreasing case).
     */
    private static IloIntExpr[] dropLabelAsc(IloCP cp, IloIntExpr[] L, int label, IloIntExpr e, int n)
            throws IloException {
        int d = L.length;
        IloIntExpr[] out = new IloIntExpr[d];
        for (int k = 0; k < d; k++) {
            IloIntExpr shift = cp.prod(e, cp.ge(L[k], label));
            IloIntExpr next = (k + 1 < d) ? L[k + 1] : cp.constant(n + 1);
            out[k] = cp.sum(L[k], cp.prod(shift, cp.diff(next, L[k])));
        }
        return out;
    }

    // =========================================================================
    //  CONSTRAINTS: connectivity (with OptRevLex; correct for regular sequences only)
    // =========================================================================

    /**
     * Upper off-diagonal: every vertex i &lt; n-1 has a neighbor v &gt; i. Correct for regular
     * sequences only: with unequal degrees only equal-degree vertices can swap labels, and
     * some connected graphs are lost (e.g. K2,3 for [2,2,2,3,3]).
     */
    private static void addUpperOffDiagonal(IloCP cp, IloIntExpr[][] A) throws IloException {
        int n = A.length;
        for (int i = 0; i < n - 1; i++) {
            IloIntExpr[] upper = new IloIntExpr[n - 1 - i];
            for (int v = i + 1; v < n; v++) upper[v - i - 1] = A[i][v];
            cp.add(cp.gt(cp.sum(upper), 0));
        }
    }

    /**
     * {@link #addUpperOffDiagonal} on the OptRevLex layout ({@code 2 5 0 0}): the largest
     * neighbor of vertex i, in the last neighbor slot deg[i]-1, is above i.
     */
    private static void addLargestNeighborAbove(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        for (int i = 0; i < M.length - 1; i++) {
            int last = Math.max(deg[i] - 1, 0);   // deg 0: slot 0 holds 0, so the constraint fails
            cp.add(cp.gt(M[i][last], i + 1));
        }
    }

    // =========================================================================
    //  SOLVING
    // =========================================================================

    /** Enumerates all solutions (printing them if title != null), then frees the solver. */
    private static Result solve(IloCP cp, IloIntVar[][] M, boolean neighbors, String title)
            throws IloException {
        cp.setParameter(IloCP.IntParam.LogVerbosity,          IloCP.ParameterValues.Quiet);
        cp.setParameter(IloCP.IntParam.SearchType,            IloCP.ParameterValues.DepthFirst);
        cp.setParameter(IloCP.IntParam.DefaultInferenceLevel, IloCP.ParameterValues.Low);
        cp.setParameter(IloCP.IntParam.MemoryDisplay,         0);

        if (title != null) System.out.println("\n##### " + title + " #####");
        long start = System.currentTimeMillis();
        int count = 0;
        cp.startNewSearch();
        while (cp.next()) {
            count++;
            if (title != null) printSolution(cp, M, neighbors, count);
        }
        cp.endSearch();
        Result result = new Result(count, System.currentTimeMillis() - start,
                cp.getInfo(IloCP.IntInfo.PeakMemoryUsage));
        cp.end();   // free the native solver: all runs share one JVM
        return result;
    }

    /** Prints the 0/1 matrix, or one line "vertex: neighbors" per vertex. */
    private static void printSolution(IloCP cp, IloIntVar[][] M, boolean neighbors, int index)
            throws IloException {
        StringBuilder sb = new StringBuilder("Solution ").append(index).append('\n');
        for (int i = 0; i < M.length; i++) {
            if (neighbors) sb.append(String.format("%3d:", i + 1));
            for (IloIntVar x : M[i]) sb.append(' ').append((int) cp.getValue(x));
            sb.append('\n');
        }
        System.out.println(sb);
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    /** The 0/1 adjacency rows of the neighbors model: A[u][v] = count(M[u], v+1). */
    private static IloIntExpr[][] adjacencyOf(IloCP cp, IloIntVar[][] M) throws IloException {
        int n = M.length;
        IloIntExpr[][] A = new IloIntExpr[n][n];
        for (int u = 0; u < n; u++) {
            for (int v = 0; v < n; v++) {
                A[u][v] = (u == v) ? cp.constant(0) : cp.count(M[u], v + 1);
            }
        }
        return A;
    }

    /** The row without the entries at positions i and j. */
    private static IloIntExpr[] without(IloIntExpr[] row, int i, int j) {
        IloIntExpr[] out = new IloIntExpr[row.length - 2];
        int k = 0;
        for (int p = 0; p < row.length; p++) {
            if (p != i && p != j) out[k++] = row[p];
        }
        return out;
    }

    /** The neighbor slots from..to-1 of a row (their position is fixed by the row layout). */
    private static IloIntExpr[] slots(IloIntVar[] row, int from, int to) {
        IloIntExpr[] out = new IloIntExpr[to - from];
        for (int k = from; k < to; k++) out[k - from] = row[k];
        return out;
    }

    /** The array in reverse order, so that lexicographic order reads it right to left. */
    private static IloIntExpr[] reverse(IloIntExpr[] a) {
        IloIntExpr[] r = new IloIntExpr[a.length];
        for (int k = 0; k < a.length; k++) r[k] = a[a.length - 1 - k];
        return r;
    }
}
