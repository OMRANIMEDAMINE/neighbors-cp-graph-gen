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
 *       as 1-based labels: an n×(n-1) matrix, empty slots hold 0. Rows are sorted as
 *       part of the symmetry breaking: increasing with zeros first for OptLex, decreasing
 *       with zeros last for OptRevLex (see {@link #addOrderedRows}).</li>
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
     * Neighbors model: an n×(n-1) matrix, row i holds the neighbors of vertex i as
     * 1-based labels in {1..n}. n-1 is the maximum degree; unused slots hold 0, e.g.
     * {@code 0 0 2 5} (OptLex) or {@code 5 2 0 0} (OptRevLex) = neighbors {2, 5}. Posted constraints:
     * <ol>
     *   <li>No self-loop: vertex i (label i+1) never lists itself</li>
     *   <li>All different except 0: a neighbor is listed at most once per row</li>
     *   <li>Degree: exactly d non-zero slots per row (only when d is fixed)</li>
     *   <li>Symmetry: v is a neighbor of u iff u is a neighbor of v</li>
     * </ol>
     * Like the adjacency base model, this one only describes the graph: the order of the
     * slots inside a row is left free here and fixed by the symmetry breaking
     * ({@link #addOrderedRows}, posted with every OptLex / OptRevLex variant).
     *
     * @param cp the CP solver instance
     * @param N  number of vertices
     * @param d  degree of every vertex, or {@link #ANY_DEGREE}
     * @return the n×(n-1) matrix of decision variables
     */
    private static IloIntVar[][] buildBaseModelNei(IloCP cp, int N, int d)
            throws IloException {
        int D = N - 1;   // slots per row = maximum degree

        IloIntVar[][] M = new IloIntVar[N][];
        for (int i = 0; i < N; i++) {
            M[i] = cp.intVarArray(D, 0, N);   // 0 = empty slot

            // 1. No self-loop
            for (int j = 0; j < D; j++) {
                cp.add(cp.neq(M[i][j], i + 1));
            }

            // 2. All different except 0: an empty slot j is mapped to the distinct
            //    value -(j+1), so only the neighbor labels must differ
            IloIntExpr[] distinct = new IloIntExpr[D];
            for (int j = 0; j < D; j++) {
                distinct[j] = cp.diff(M[i][j], cp.prod(j + 1, cp.eq(M[i][j], 0)));
            }
            cp.add(cp.allDiff(distinct));

            // 3. Degree: d neighbors = N-1-d empty slots (only when d is fixed)
            if (d != ANY_DEGREE) {
                cp.add(cp.eq(cp.count(M[i], 0), D - d));
            }
        }

        // 4. Symmetry: count(v in N(u)) == count(u in N(v)), each count being 0 or 1
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
    //  SYMMETRY BREAKING  (ordered rows, posted with OptLex and OptRevLex)
    // =========================================================================

    /**
     * Neighbors model: breaks the symmetry between the slots of a row, so every neighbor
     * set has a single row, sorted in the same direction as the order between rows:
     * <ul>
     *   <li>{@code descending = false} (OptLex, columns read left to right, "&lt;="):
     *       0 is the smallest value, so the zeros come first, then the neighbors
     *       increasing, e.g. {@code 0 0 2 5}.</li>
     *   <li>{@code descending = true} (OptRevLex, columns read right to left, "&gt;="):
     *       the neighbors come first, decreasing, then the zeros, e.g. {@code 5 2 0 0}.</li>
     * </ul>
     * The order is not strict, so the zeros may repeat; the neighbors are still strictly
     * ordered because the base model makes them all different (all different except 0).
     * Required by the OptLex / OptRevLex Nei variants (the *Direct ones read the rows as
     * sorted lists) and by {@link #addOffDiagNeiConnectivity}.
     */
    private static void addOrderedRows(IloCP cp, IloIntVar[][] M, boolean descending)
            throws IloException {
        for (IloIntVar[] row : M) {
            for (int j = 0; j < row.length - 1; j++) {
                cp.add(descending
                        ? cp.ge(row[j], row[j + 1])
                        : cp.le(row[j], row[j + 1]));
            }
        }
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
                        reverse(arrayNew(C[j], i, j)))
                );
            }
        }
    }

    /**
     * Neighbors model, OptRevLex posted directly on the neighbor lists (no 0/1 rows).
     * Same constraint as {@link #addOptRevLexNei}: for every pair i &lt; j,
     * <pre>
     *   rev(M[i] − {j+1})  ≤lex  rev(M[j] − {i+1})
     * </pre>
     * where {@code rev} is the 0/1 row read right-to-left, i.e. the neighbor list itself:
     * rows are already sorted descending with zeros last ({@link #addOrderedRows}), and
     * {@code −} deletes that label (see {@link #dropLabel}). Removing columns i and j from
     * both 0/1 rows leaves the vectors of N(i)\{j} and N(j)\{i}; reverse lexicographic order
     * on those vectors means "the largest element of the symmetric difference belongs to
     * N(j)\{i}", which is exactly lexicographic order on the descending lists.
     * Note: removing label j from row i and label i from row j is correct on the lists,
     * but NOT on the 0/1 rows, where it would misalign the columns between i and j.
     */
    private static void addOptRevLexNeiDirect(IloCP cp, IloIntVar[][] M, int N)
            throws IloException {
        if (N < 3) return;   // with labels i and j removed nothing is left to compare
        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                // e = 1 iff i and j are adjacent (= count(M[j], i+1) by symmetry)
                IloIntExpr e = cp.count(M[i], j + 1);
                cp.add(cp.lexicographic(
                        dropLabel(cp, M[i], j + 1, e),
                        dropLabel(cp, M[j], i + 1, e)));
            }
        }
    }

    /**
     * Returns the neighbor list of a row (already descending, zeros last) with
     * {@code label} deleted, as n-2 expressions. {@code e} is 1 iff {@code label} is in the
     * row. Every entry at or after the position of {@code label} moves one slot to the left:
     * <pre>
     *   R'[k] = R[k] + s_k · (R[k+1] − R[k]),   s_k = e · [R[k] ≤ label]
     * </pre>
     * When e = 0 the row has at most n-2 neighbors, so dropping its last slot (a 0) is harmless.
     */
    private static IloIntExpr[] dropLabel(IloCP cp, IloIntVar[] row, int label, IloIntExpr e)
            throws IloException {
        int D = row.length;
        IloIntExpr[] R = row;   // sorted descending by addOrderedRows(.., true)
        IloIntExpr[] out = new IloIntExpr[D - 1];
        for (int k = 0; k < D - 1; k++) {
            IloIntExpr shifted = cp.prod(e, cp.le(R[k], label));   // label sits at a slot <= k
            out[k] = cp.sum(R[k], cp.prod(shifted, cp.diff(R[k + 1], R[k])));
        }
        return out;
    }

    // =========================================================================
    //  SYMMETRY BREAKING  (OptLex, Codish et al.: columns read left to right)
    // =========================================================================

    /**
     * Adjacency model, OptLex: for every pair of rows i &lt; j, row i is
     * lexicographically ≤ row j when columns i and j are removed, the left-most
     * column being the most significant.
     */
    private static void addOptLex(IloCP cp, IloIntVar[][] M) throws IloException {
        int N = M.length;
        if (N < 3) return;   // with columns i and j removed nothing is left to compare
        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                cp.add(cp.lexicographic(arrayNew(M[i], i, j), arrayNew(M[j], i, j)));
            }
        }
    }

    /**
     * Neighbors model, OptLex: same ordering as {@link #addOptLex}, posted on the 0/1
     * adjacency rows rebuilt as expressions over the neighbor lists
     * ({@code C[i][v] = count(M[i], v+1)}).
     */
    private static void addOptLexNei(IloCP cp, IloIntVar[][] M, int N) throws IloException {
        IloIntExpr[][] C = new IloIntExpr[N][N];
        for (int i = 0; i < N; i++) {
            for (int v = 0; v < N; v++) {
                C[i][v] = (v == i) ? cp.constant(0) : cp.count(M[i], v + 1);
            }
        }

        if (N < 3) return;   // with columns i and j removed nothing is left to compare
        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                cp.add(cp.lexicographic(arrayNew(C[i], i, j), arrayNew(C[j], i, j)));
            }
        }
    }

    /**
     * Neighbors model, OptLex posted directly on the neighbor lists. Same constraint as
     * {@link #addOptLexNei}: for every pair i &lt; j, with S_i = N(i)\{j} and S_j = N(j)\{i},
     * <pre>
     *   asc(S_j)  ≤lex  asc(S_i)
     * </pre>
     * where {@code asc} lists a set in increasing order padded at the END with the sentinel
     * n+1 (see {@link #dropLabelAsc}). Read left to right, the 0/1 rows first differ at the
     * SMALLEST element w of S_i Δ S_j, and row i ≤ row j iff w ∈ S_j. In the padded
     * increasing lists the first difference also sits at w, and the list holding w is the
     * smaller one, hence the swapped arguments.
     *
     * <p>Unlike OptRevLex, the significant end of a row (its smallest neighbor) is preceded
     * by a variable number of padding zeros, so each list is read from position
     * {@code count(M[i], 0)} with {@code element} expressions.
     */
    private static void addOptLexNeiDirect(IloCP cp, IloIntVar[][] M, int N)
            throws IloException {
        if (N < 3) return;   // with labels i and j removed nothing is left to compare
        int D = N - 1;
        IloIntExpr sentinel = cp.constant(N + 1);

        // Per row: ext = row followed by D sentinels, z = number of padding zeros,
        // head[k] = k-th smallest neighbor (or the sentinel) = ext[z + k]
        IloIntExpr[][] ext = new IloIntExpr[N][2 * D];
        IloIntExpr[] z = new IloIntExpr[N];
        IloIntExpr[][] head = new IloIntExpr[N][D - 1];
        for (int v = 0; v < N; v++) {
            for (int p = 0; p < 2 * D; p++) {
                ext[v][p] = (p < D) ? M[v][p] : sentinel;
            }
            z[v] = cp.count(M[v], 0);
            for (int k = 0; k < D - 1; k++) {
                head[v][k] = cp.element(ext[v], cp.sum(z[v], k));
            }
        }

        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                // e = 1 iff i and j are adjacent (= count(M[j], i+1) by symmetry)
                IloIntExpr e = cp.count(M[i], j + 1);
                cp.add(cp.lexicographic(
                        dropLabelAsc(cp, ext[j], z[j], head[j], i + 1, e),
                        dropLabelAsc(cp, ext[i], z[i], head[i], j + 1, e)));
            }
        }
    }

    /**
     * Returns the neighbors of a row in increasing order, padded at the end with the
     * sentinel n+1, with {@code label} deleted, as n-2 expressions. {@code e} is 1 iff
     * {@code label} is in the row. Slot k reads position z+k of {@code ext}, or z+k+1 once
     * the label has been passed:
     * <pre>
     *   out[k] = ext[z + k + s_k],   s_k = e · [head[k] ≥ label]
     * </pre>
     * When e = 0 the row has at most n-2 neighbors, so n-2 slots hold them all.
     */
    private static IloIntExpr[] dropLabelAsc(IloCP cp, IloIntExpr[] ext, IloIntExpr z,
                                             IloIntExpr[] head, int label, IloIntExpr e)
            throws IloException {
        IloIntExpr[] out = new IloIntExpr[head.length];
        for (int k = 0; k < head.length; k++) {
            IloIntExpr shifted = cp.prod(e, cp.ge(head[k], label));   // label at a slot <= k
            out[k] = cp.element(ext, cp.sum(cp.sum(z, k), shifted));
        }
        return out;
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
     * sorted, so it reduces to "the largest neighbor of row i is above i's label", a single
     * unary constraint per vertex, O(n) in total. The largest neighbor is the first slot
     * for descending rows (OptRevLex), the last slot for increasing rows (OptLex).
     */
    private static void addOffDiagNeiConnectivity(IloCP cp, IloIntVar[][] M, boolean descending)
            throws IloException {
        int N = M.length;
        int max = descending ? 0 : M[0].length - 1;
        for (int i = 0; i < N - 1; i++) {
            cp.add(cp.gt(M[i][max], i + 1));
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
            addOrderedRows(cp, M, true);
            addOptRevLexNei(cp, M, n);
            if (connectivity) {
                addOffDiagNeiConnectivity(cp, M, true);
            }
            configureSolver(cp);
            return collectResults(cp, M, Encoding.NEIGHBORS, title);
        } catch (IloException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Neighbors model + OptRevLex posted directly on the neighbor lists
     * ({@link #addOptRevLexNeiDirect}). Finds the same solutions as {@link #testOptRevLexNei}.
     *
     * @param n            number of vertices (n >= 2)
     * @param d            degree of every vertex (d >= 1), or {@link #ANY_DEGREE} for all graphs
     * @param connectivity if true, also posts the upper off-diagonal connectivity constraint
     * @param title        if not null, prints every solution under this title
     */
    public static Result testOptRevLexNeiDirect(int n, int d, boolean connectivity, String title) {
        try {
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelNei(cp, n, d);
            addOrderedRows(cp, M, true);
            addOptRevLexNeiDirect(cp, M, n);
            if (connectivity) {
                addOffDiagNeiConnectivity(cp, M, true);
            }
            configureSolver(cp);
            return collectResults(cp, M, Encoding.NEIGHBORS, title);
        } catch (IloException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Adjacency model + OptLex.
     *
     * @param n            number of vertices (n >= 2)
     * @param d            degree of every vertex, or {@link #ANY_DEGREE} for all graphs
     * @param connectivity if true, also posts the upper off-diagonal connectivity constraint
     * @param title        if not null, prints every solution under this title
     */
    public static Result testOptLexAdj(int n, int d, boolean connectivity, String title) {
        try {
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelAdj(cp, n, d);
            addOptLex(cp, M);
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
     * Neighbors model + OptLex on the rebuilt 0/1 rows.
     *
     * @param n            number of vertices (n >= 2)
     * @param d            degree of every vertex (d >= 1), or {@link #ANY_DEGREE} for all graphs
     * @param connectivity if true, also posts the upper off-diagonal connectivity constraint
     * @param title        if not null, prints every solution under this title
     */
    public static Result testOptLexNei(int n, int d, boolean connectivity, String title) {
        try {
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelNei(cp, n, d);
            addOrderedRows(cp, M, false);
            addOptLexNei(cp, M, n);
            if (connectivity) {
                addOffDiagNeiConnectivity(cp, M, false);
            }
            configureSolver(cp);
            return collectResults(cp, M, Encoding.NEIGHBORS, title);
        } catch (IloException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Neighbors model + OptLex posted directly on the neighbor lists
     * ({@link #addOptLexNeiDirect}). Finds the same solutions as {@link #testOptLexNei}.
     *
     * @param n            number of vertices (n >= 2)
     * @param d            degree of every vertex (d >= 1), or {@link #ANY_DEGREE} for all graphs
     * @param connectivity if true, also posts the upper off-diagonal connectivity constraint
     * @param title        if not null, prints every solution under this title
     */
    public static Result testOptLexNeiDirect(int n, int d, boolean connectivity, String title) {
        try {
            IloCP cp = new IloCP();
            IloIntVar[][] M = buildBaseModelNei(cp, n, d);
            addOrderedRows(cp, M, false);
            addOptLexNeiDirect(cp, M, n);
            if (connectivity) {
                addOffDiagNeiConnectivity(cp, M, false);
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
