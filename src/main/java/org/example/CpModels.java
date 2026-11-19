package org.example;

import ilog.concert.IloConstraint;
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
 *       vertex i as labels 1..n, increasing, then the empty slots holding 0
 *       ({@code 2 5 0 0}). The zeros and the neighbor labels are fixed by the variable
 *       domains when the variables are created. Used with OptRevLex only.</li>
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
        // addOptRevLexNeiSlots(cp, M, deg);
        // addOptRevLexNeiSlotsLex(cp, M, deg);
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
        for (int i = 0; i < n-1; i++) {
            for (int j = i + 1; j < n; j++) {
              if (deg[i] != deg[i+1]) continue;
                cp.add(cp.lexicographic(reverse(without(A[i], i, i+1)), reverse(without(A[i+1], i, i+1))));
            }
        }
    }

    /**
     * Neighbors, OptRevLex: for every pair i &lt; j with deg[i] = deg[j] = d, the labels i+1 and
     * j+1 are removed from both rows, and the neighbor slots that remain are compared slot by
     * slot from the last neighbor slot down to the first (largest neighbor first):
     * <pre>
     *   i, j adjacent:      reverse(M[i][0..d-1] without j+1)  ≤lex  reverse(M[j][0..d-1] without i+1)   (d-1 slots)
     *   i, j not adjacent:  reverse(M[i][0..d-1])              ≤lex  reverse(M[j][0..d-1])               (d slots)
     * </pre>
     * Row i never holds i+1 and row j never holds j+1, so only j+1 (in row i) and i+1 (in row j)
     * can actually be removed, and only when i and j are adjacent. The zero slots d..D-1 are
     * left out. Both rows keep the same number of neighbors, so comparing them largest first is
     * the Adj OptRevLex order on rows i and j without columns i and j.
     * <pre>
     *   1: 2 3 4  2: 1 5 6  3: 1 5 6  4: 1 5 6  5: 2 3 4  6: 2 3 4    rejected: rows 4, 5 give 6 1 &gt; 3 2
     *   1: 2 3 4  2: 1 3 5  3: 1 2 6  4: 1 5 6  5: 2 4 6  6: 3 4 5    accepted
     * </pre>
     */
    private static void addOptRevLexNeiDir(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        int n = deg.length;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int d = deg[i];
                if (d != deg[j] || d == 0) continue;
                IloIntExpr[] rowI = neighborSlots(M[i], d), rowJ = neighborSlots(M[j], d);
                IloConstraint adjacent = cp.eq(cp.count(M[i], j + 1), 1);
                cp.add(cp.ifThen(cp.not(adjacent), cp.lexicographic(reverse(rowI), reverse(rowJ))));
                if (d > 1) {   // with one neighbor, removing it leaves nothing to compare
                    cp.add(cp.ifThen(adjacent, cp.lexicographic(reverse(dropLabel(cp, rowI, j + 1)),
                                                                reverse(dropLabel(cp, rowJ, i + 1)))));
                }
            }
        }
    }

    /** The first d slots of a neighbor row: its neighbors, without the zero slots. */
    private static IloIntExpr[] neighborSlots(IloIntVar[] row, int d) {
        IloIntExpr[] out = new IloIntExpr[d];
        System.arraycopy(row, 0, out, 0, d);
        return out;
    }

    /**
     * The row with the slot p holding {@code label} deleted: D-1 slots, no 0 added. Only used
     * when the label is in the row. The slots after p move one slot to the left:
     * <pre>
     *   out[k] = row[k+1] if k ≥ p, else row[k]                   (k = 0 .. D-2)
     *   [k ≥ p] = [row[0] = label] + ... + [row[k] = label]
     * </pre>
     * E.g. {@code 2 5 0 0} without 2 → {@code 5 0 0}.
     * The test "slot k is at or after the label" is a running count, which propagates better
     * than computing p as a number.
     */
    private static IloIntExpr[] dropLabel(IloCP cp, IloIntExpr[] row, int label) throws IloException {
        int D = row.length;
        IloIntExpr[] out = new IloIntExpr[D - 1];
        IloIntExpr atOrAfter = cp.constant(0);   // [k ≥ p]
        for (int k = 0; k < D - 1; k++) {
            atOrAfter = cp.sum(atOrAfter, cp.eq(row[k], label));
            out[k] = cp.sum(row[k], cp.prod(atOrAfter, cp.diff(row[k + 1], row[k])));
        }
        return out;
    }




    /**
     * Neighbors, OptRevLex, slot par slot. Pour chaque paire i < j de même degré d :
     * C_i = reverse(ligne i sans j+1), C_j = reverse(ligne j sans i+1), puis
     *   (C_i[0..k-1] = C_j[0..k-1])  ⇒  C_i[k] ≤ C_j[k]      pour k = 0 .. d-1
     * Même ensemble de solutions que addOptRevLexNeiDir.
     */
    private static void addOptRevLexNeiSlots(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        int n = deg.length;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int d = deg[i];
                if (d != deg[j] || d == 0) continue;
                IloIntExpr[] rowI = neighborSlots(M[i], d), rowJ = neighborSlots(M[j], d);
                IloIntExpr adj = cp.count(rowI, j + 1);
                // Les deux lignes réduites de la paire (i, j), d slots chacune
                IloIntExpr[] Ci = reverse(dropOrKeep(cp, rowI, j + 1, adj));
                IloIntExpr[] Cj = reverse(dropOrKeep(cp, rowJ, i + 1, adj));

                IloConstraint eqSoFar = null;                 // null = vrai (préfixe vide)
                for (int k = 0; k < d; k++) {
                    IloConstraint le = cp.le(Ci[k], Cj[k]);
                    cp.add(eqSoFar == null ? le : cp.ifThen(eqSoFar, le));
                    if (k < d - 1) {
                        IloConstraint eqK = cp.eq(Ci[k], Cj[k]);
                        eqSoFar = (eqSoFar == null) ? eqK : cp.and(eqSoFar, eqK);
                    }
                }
            }
        }
    }
    /**
     * Comme {@link #addOptRevLexNeiSlots}, mais la chaîne slot par slot est remplacée par la
     * contrainte globale : C_i ≤lex C_j. Même ensemble de solutions que addOptRevLexNeiDir.
     */
    private static void addOptRevLexNeiSlotsLex(IloCP cp, IloIntVar[][] M, int[] deg) throws IloException {
        int n = deg.length;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int d = deg[i];
                if (d != deg[j] || d == 0) continue;
                IloIntExpr[] rowI = neighborSlots(M[i], d), rowJ = neighborSlots(M[j], d);
                IloIntExpr adj = cp.count(rowI, j + 1);
                cp.add(cp.lexicographic(reverse(dropOrKeep(cp, rowI, j + 1, adj)),
                                        reverse(dropOrKeep(cp, rowJ, i + 1, adj))));
            }
        }
    }

    /**
     * Ligne réduite d'un sommet pour la paire (i, j), toujours d slots :
     *   adj = 0 : la ligne inchangée                       2 5 7 → 2 5 7
     *   adj = 1 : la ligne sans label, puis un 0           2 5 7 sans 5 → 2 7 0
     * Après reverse, le 0 arrive en tête (0 7 2) ; il vaut 0 dans les deux lignes de la
     * paire, donc il ne départage jamais : la comparaison porte sur les d-1 vrais voisins.
     * Préconditions : ligne strictement croissante ; label présent dans la ligne ⇔ adj = 1.
     */
    private static IloIntExpr[] dropOrKeep(IloCP cp, IloIntExpr[] row, int label, IloIntExpr adj)
            throws IloException {
        int d = row.length;
        IloIntExpr[] out = new IloIntExpr[d];
        IloIntExpr atOrAfter = cp.constant(0);              // [k ≥ p] ; reste 0 si label absent
        for (int k = 0; k < d - 1; k++) {
            atOrAfter = cp.sum(atOrAfter, cp.eq(row[k], label));
            out[k] = cp.sum(row[k], cp.prod(atOrAfter, cp.diff(row[k + 1], row[k])));
        }
        out[d - 1] = cp.prod(cp.diff(1, adj), row[d - 1]); // dernier slot, ou 0 si adjacents
        return out;
    }

    // =========================================================================
    //  CONSTRAINTS: connectivity (last row ignored)
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
     * Nei ({@code 2 5 0 0}): the largest neighbor of vertex i, in the last neighbor slot
     * deg[i]-1, is above i. Posted for every row except the last. Like the Adj upper
     * off-diagonal ("vertex i has a neighbor above i"), correct for regular sequences only.
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
