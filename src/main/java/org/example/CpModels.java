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
 *       vertex i as labels 1..n, empty slots hold 0. OptRevLex lists the neighbors
 *       increasing, then the zeros ({@code 2 5 0 0}); OptLex is the mirror image, the zeros
 *       then the neighbors decreasing ({@code 0 0 5 2}). The zeros and the neighbor labels
 *       are fixed by the variable domains when the variables are created.</li>
 * </ul>
 *
 * <p>The file has three layers:
 * <ol>
 *   <li><b>Models</b>: one public method per configuration (one table column). Each one
 *       builds a base model, posts its constraints and solves.</li>
 *   <li><b>Base models</b>: the variables of a graph with the given degrees, and the
 *       constraints that make it a simple undirected graph.</li>
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

    /** Neighbor lists + OptLex posted directly on the lists ({@link #addOptLexNeiDir}). */
    public static Result optLexNeiDir(int[] deg, String title) throws IloException {
        IloCP cp = new IloCP();
        IloIntVar[][] M = buildLexNeighborsModel(cp, deg);
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

    /**
     * Neighbor lists + OptRevLex posted directly on the lists ({@link #addOptRevLexNeiDir}),
     * optionally connected graphs only.
     */
    public static Result optRevLexNeiDir(int[] deg, boolean connectivity, String title)
            throws IloException {
        IloCP cp = new IloCP();
        IloIntVar[][] M = buildRevLexNeighborsModel(cp, deg);
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
     * OptLex neighbor rows, the mirror image of {@link #buildRevLexNeighborsModel}: in row i,
     * slots 0..D-deg[i]-1 are created as 0 and the last deg[i] slots hold the neighbors,
     * strictly decreasing: {@code 0 0 5 2}.
     */
    private static IloIntVar[][] buildLexNeighborsModel(IloCP cp, int[] deg) throws IloException {
        int n = deg.length, D = maxDegree(deg);
        IloIntVar[][] M = new IloIntVar[n][D];
        for (int i = 0; i < n; i++) {
            int first = D - deg[i];                                  // first neighbor slot
            for (int k = 0; k < D; k++) M[i][k] = (k < first) ? cp.intVar(0, 0) : neighborVar(cp, n, i);
            for (int k = first; k + 1 < D; k++) cp.add(cp.gt(M[i][k], M[i][k + 1]));
        }
        addUndirected(cp, M);
        return M;
    }

    /**
     * OptRevLex neighbor rows: in row i, the first deg[i] slots hold the neighbors, strictly
     * increasing, and slots deg[i]..D-1 are created as 0: {@code 2 5 0 0}.
     */
    private static IloIntVar[][] buildRevLexNeighborsModel(IloCP cp, int[] deg) throws IloException {
        int n = deg.length, D = maxDegree(deg);
        IloIntVar[][] M = new IloIntVar[n][D];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < D; k++) M[i][k] = (k < deg[i]) ? neighborVar(cp, n, i) : cp.intVar(0, 0);
            for (int k = 0; k + 1 < deg[i]; k++) cp.add(cp.lt(M[i][k], M[i][k + 1]));
        }
        addUndirected(cp, M);
        return M;
    }

    /**
     * A neighbor slot of vertex i: domain {1..n} without i's own label i+1 (no self-loop).
     * With the zero slots created as 0 and the strict order between neighbor slots, row i
     * holds exactly deg[i] distinct neighbors: no degree count, no all-different.
     */
    private static IloIntVar neighborVar(IloCP cp, int n, int i) throws IloException {
        int[] labels = new int[n - 1];
        for (int v = 1, k = 0; v <= n; v++) if (v != i + 1) labels[k++] = v;
        return cp.intVar(labels);
    }

    /** v is a neighbor of u iff u is a neighbor of v (each count is 0 or 1). */
    private static void addUndirected(IloCP cp, IloIntVar[][] M) throws IloException {
        int n = M.length;
        for (int u = 0; u < n; u++) {
            for (int v = u + 1; v < n; v++) {
                cp.add(cp.eq(cp.count(M[u], v + 1), cp.count(M[v], u + 1)));
            }
        }
    }

    /** Largest degree of the sequence (at least 1, so that every row has a slot). */
    private static int maxDegree(int[] deg) {
        int D = 1;
        for (int d : deg) D = Math.max(D, d);
        return D;
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

    /**
     * Neighbors, OptRevLex posted directly on the rows. For every pair i &lt; j with
     * deg[i] = deg[j]: drop label j+1 from row i and label i+1 from row j (if found), then
     * compare the two rows with RevLex (last slot first):
     * <pre>
     *   reverse(M[i] without j+1)  ≤lex  reverse(M[j] without i+1)
     * </pre>
     * Read right to left, the 0/1 rows first differ at the LARGEST vertex w of
     * N(i)\{j} Δ N(j)\{i}, and row i ≤ row j iff w ∈ N(j). The rows list the neighbors
     * increasing, so read from the last slot they first differ at w too, and the row holding
     * w is the larger one: the same order as {@link #addOptRevLex} on the 0/1 rows. The fixed zeros are in
     * the same slots of both rows (equal degrees), so they never decide.
     */
    private static void addOptRevLexNeiDir(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        int n = deg.length;
        if (n < 3) return;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (deg[i] != deg[j]) continue;
                IloIntExpr[] ri = dropLabel(cp, M[i], j + 1);   // row i without j+1
                IloIntExpr[] rj = dropLabel(cp, M[j], i + 1);   // row j without i+1
                cp.add(cp.lexicographic(reverse(ri), reverse(rj)));
            }
        }
    }

    /**
     * Neighbors, OptLex posted directly on the rows. For every pair i &lt; j with
     * deg[i] = deg[j]: drop label j+1 from row i and label i+1 from row j (if found), then
     * compare the two rows from the last slot (smallest neighbor first), arguments swapped:
     * <pre>
     *   reverse(M[j] without i+1)  ≤lex  reverse(M[i] without j+1)
     * </pre>
     * Read left to right, the 0/1 rows first differ at the SMALLEST vertex w of
     * N(i)\{j} Δ N(j)\{i}, and row i ≤ row j iff w ∈ N(j). The rows list the neighbors
     * decreasing ({@code 0 0 5 2}), so read from the last slot they first differ at w too,
     * and the row holding w is the SMALLER one, hence the swap: the same order as
     * {@link #addOptLex} on the 0/1 rows.
     */
    private static void addOptLexNeiDir(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        int n = deg.length;
        if (n < 3) return;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (deg[i] != deg[j]) continue;
                IloIntExpr[] ri = dropLabel(cp, M[i], j + 1);   // row i without j+1
                IloIntExpr[] rj = dropLabel(cp, M[j], i + 1);   // row j without i+1
                cp.add(cp.lexicographic(reverse(rj), reverse(ri)));
            }
        }
    }

    /**
     * The row without the slot p holding {@code label} (unchanged if the label is not in
     * the row). Slots 0..p-1 move one slot right and a 0 enters slot 0. Both OptLex and
     * OptRevLex compare the rows from the last slot, so the freed slot is the LAST one
     * compared. E.g. {@code 2 5 0 0} without 5 → {@code 0 2 0 0}; {@code 0 0 5 2} without 5
     * → {@code 0 0 0 2}.
     * <pre>
     *   out[k] = row[k-1] if k ≤ p, else row[k]                   (row[-1] = 0)
     *   [k ≤ p] = [row[k] = label] + ... + [row[D-1] = label]     (0 for all k if absent)
     * </pre>
     * The test "slot k is at or before the label" is built directly as a count from the
     * right instead of computing p as a number: same meaning, much better propagation.
     */
    private static IloIntExpr[] dropLabel(IloCP cp, IloIntExpr[] row, int label) throws IloException {
        int D = row.length;
        IloIntExpr[] out = new IloIntExpr[D];
        IloIntExpr atOrBefore = cp.constant(0);   // [k ≤ p]
        for (int k = D - 1; k >= 0; k--) {
            atOrBefore = cp.sum(atOrBefore, cp.eq(row[k], label));
            IloIntExpr prev = (k > 0) ? row[k - 1] : cp.constant(0);
            out[k] = cp.sum(row[k], cp.prod(atOrBefore, cp.diff(prev, row[k])));
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

    /** The row without the entries at positions i and j. */
    private static IloIntExpr[] without(IloIntExpr[] row, int i, int j) {
        IloIntExpr[] out = new IloIntExpr[row.length - 2];
        int k = 0;
        for (int p = 0; p < row.length; p++) {
            if (p != i && p != j) out[k++] = row[p];
        }
        return out;
    }

    /** The array in reverse order, so that lexicographic order reads it right to left. */
    private static IloIntExpr[] reverse(IloIntExpr[] a) {
        IloIntExpr[] r = new IloIntExpr[a.length];
        for (int k = 0; k < a.length; k++) r[k] = a[a.length - 1 - k];
        return r;
    }


}
