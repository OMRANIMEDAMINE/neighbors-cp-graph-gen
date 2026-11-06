package org.example;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntExpr;
import ilog.concert.IloIntVar;
import ilog.cp.IloCP;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * CP Optimizer models that enumerate all graphs on n vertices (any degrees).
 *
 * <p>Two encodings of the same graph are available:
 * <ul>
 *   <li>{@link Encoding#ADJACENCY}: an n×n 0/1 adjacency matrix.</li>
 *   <li>{@link Encoding#NEIGHBORS}: for each vertex, the sorted list of its neighbors
 *       (vertex labels are 1-based) in n-1 slots, left-padded with 0.</li>
 * </ul>
 *
 * <p>Both encodings use the OptRevLex symmetry-breaking constraints. The upper
 * off-diagonal connectivity constraints can be added on top of them.
 */
public final class CpModels {

    public enum Encoding { ADJACENCY, NEIGHBORS }

    /** Which connectivity constraints to add on top of OptRevLex. */
    public enum Connectivity {
        /** None: every graph is enumerated. */
        NONE,
        /** Upper off-diagonal: {@code addAdjacencyConnectivity} / {@code addNeighborsConnectivity}. */
        UPPER_OFF_DIAGONAL,
        /** Neighbors encoding only: {@code addNeighborsConnectivityLastValidSlot}. */
        LAST_VALID_SLOT
    }

    /**
     * Notified of every solution found. {@code values.get()} reads the solution:
     * an n×n adjacency matrix for {@link Encoding#ADJACENCY}, n×(n-1) zero-padded
     * neighbor lists for {@link Encoding#NEIGHBORS}. A listener that doesn't need a solution can
     * skip the call, so nothing is read.
     */
    @FunctionalInterface
    public interface SolutionListener {
        void onSolution(Supplier<int[][]> values);
    }

    private CpModels() {}

    /**
     * Builds the model, enumerates every solution, and returns the solution count
     * and search statistics.
     *
     * @param n         number of vertices
     * @param encoding  graph encoding to use
     * @param connectivity which connectivity constraints to add
     * @param listener     notified of each solution, or {@code null}
     */
    public static Result solve(int n, Encoding encoding, Connectivity connectivity, SolutionListener listener) {
        IloCP cp = null;
        try {
            cp = new IloCP();
            configureSolver(cp);

            // Note: constraint posting order affects CP Optimizer's search statistics.
            if (encoding == Encoding.ADJACENCY) {
                IloIntVar[][] a = buildAdjacencyModel(cp, n);
                addOptRevLex(cp, a);
                switch (connectivity) {
                    case NONE -> {}
                    case UPPER_OFF_DIAGONAL -> addAdjacencyConnectivity(cp, a);
                    case LAST_VALID_SLOT -> throw new IllegalArgumentException(
                            "LAST_VALID_SLOT applies to the neighbors encoding only");
                }
                return enumerate(cp, a, listener);
            } else {
                IloIntVar[][] nb = buildNeighborsModel(cp, n);
                addOptRevLex(cp, adjacencyFromNeighbors(cp, nb));
                switch (connectivity) {
                    case NONE -> {}
                    case UPPER_OFF_DIAGONAL -> addNeighborsConnectivity(cp, nb);
                    case LAST_VALID_SLOT -> addNeighborsConnectivityLastValidSlot(cp, nb);
                }
                return enumerate(cp, nb, listener);
            }
        } catch (IloException e) {
            throw new RuntimeException(e);
        } finally {
            if (cp != null) cp.end();   // release the solver's native memory before the next run
        }
    }

    private static void configureSolver(IloCP cp) throws IloException {
        cp.setParameter(IloCP.IntParam.LogVerbosity, IloCP.ParameterValues.Quiet);
        cp.setParameter(IloCP.IntParam.SearchType, IloCP.ParameterValues.DepthFirst);
        cp.setParameter(IloCP.IntParam.DefaultInferenceLevel, IloCP.ParameterValues.Low);
        cp.setParameter(IloCP.IntParam.MemoryDisplay, 0);
    }

    // =========================================================================
    //  BASE MODELS
    // =========================================================================

    /**
     * Adjacency encoding: {@code A[i][j] = 1} iff vertices i and j are adjacent.
     * Posts: no self-loops and the matrix is symmetric. Degrees are free.
     */
    private static IloIntVar[][] buildAdjacencyModel(IloCP cp, int n) throws IloException {
        IloIntVar[][] a = new IloIntVar[n][];
        for (int i = 0; i < n; i++) {
            a[i] = cp.intVarArray(n, 0, 1);
        }
        for (int i = 0; i < n; i++) {
            cp.add(cp.eq(a[i][i], 0));
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                cp.add(cp.eq(a[i][j], a[j][i]));
            }
        }
        return a;
    }

    /**
     * Neighbors encoding: {@code N[i]} has n-1 slots (the largest possible degree) holding
     * the neighbors of vertex i+1 (values in 1..n), padded with 0 for unused slots.
     * The padding comes first and the neighbors follow in strictly increasing order, e.g.
     * {@code [0, 0, 2, 5]}, so the last slot is always the largest neighbor (0 if isolated).
     * Posts: no self-loops, sorted lists, and u ∈ N[v] ⇔ v ∈ N[u].
     */
    private static IloIntVar[][] buildNeighborsModel(IloCP cp, int n) throws IloException {
        int slots = n - 1;
        IloIntVar[][] nb = new IloIntVar[n][];
        for (int i = 0; i < n; i++) {
            nb[i] = cp.intVarArray(slots, 0, n);
            for (int k = 0; k < slots; k++) {
                cp.add(cp.neq(nb[i][k], i + 1));
            }
            // A slot is padding (0) or smaller than the next one: zeros first, then increasing.
            for (int k = 0; k < slots - 1; k++) {
                cp.add(cp.or(cp.eq(nb[i][k], 0), cp.lt(nb[i][k], nb[i][k + 1])));
            }
        }
        for (int u = 0; u < n; u++) {
            for (int v = u + 1; v < n; v++) {
                cp.add(cp.eq(cp.count(nb[u], v + 1), cp.count(nb[v], u + 1)));
            }
        }
        return nb;
    }

    /**
     * Expresses the neighbor lists as a 0/1 adjacency matrix of expressions
     * (no extra variables): {@code C[i][v] = 1} iff v+1 ∈ N[i].
     */
    private static IloIntExpr[][] adjacencyFromNeighbors(IloCP cp, IloIntVar[][] nb) throws IloException {
        int n = nb.length;
        IloIntExpr[][] c = new IloIntExpr[n][n];
        for (int i = 0; i < n; i++) {
            for (int v = 0; v < n; v++) {
                c[i][v] = (v == i) ? cp.constant(0) : cp.count(nb[i], v + 1);
            }
        }
        return c;
    }

    // =========================================================================
    //  SYMMETRY BREAKING
    // =========================================================================

    /**
     * OptRevLex: for every pair of rows i &lt; j, with columns i and j removed,
     * row i is lexicographically ≤ row j when read right to left
     * (the right-most column is the most significant).
     */
    private static void addOptRevLex(IloCP cp, IloIntExpr[][] a) throws IloException {
        int n = a.length;
        for (int i = 0; i < n - 1; i++) {
            for (int j = i + 1; j < n; j++) {
                cp.add(cp.lexicographic(
                        reversedWithout(a[i], i, j),
                        reversedWithout(a[j], i, j)));
            }
        }
    }

    // =========================================================================
    //  CONNECTIVITY (upper off-diagonal)
    // =========================================================================

    /** Every vertex i &lt; n-1 has at least one neighbor with a larger label. */
    private static void addAdjacencyConnectivity(IloCP cp, IloIntVar[][] a) throws IloException {
        int n = a.length;
        for (int i = 0; i < n - 1; i++) {
            IloIntVar[] upper = Arrays.copyOfRange(a[i], i + 1, n);
            cp.add(cp.gt(cp.sum(upper), 0));
        }
    }

    /** Same constraint for the neighbors encoding: the largest neighbor of i is above i. */
    private static void addNeighborsConnectivity(IloCP cp, IloIntVar[][] nb) throws IloException {
        int n = nb.length;
        int d = nb[0].length;
        for (int i = 0; i < n - 1; i++) {
            cp.add(cp.gt(nb[i][d - 1], i + 1));
        }
    }

    /**
     * Connectivity on the last valid slot: for every vertex i &lt; n-1, the row has at least
     * one non-zero slot, and the last valid slot (the last non-zero slot, i.e. the one
     * followed only by zeros, or the last slot) holds a label above i.
     */
    private static void addNeighborsConnectivityLastValidSlot(IloCP cp, IloIntVar[][] nb) throws IloException {
        int n = nb.length;
        int slots = nb[0].length;
        for (int i = 0; i < n - 1; i++) {
            cp.add(cp.gt(cp.sum(nb[i]), 0));   // at least one valid slot
            for (int k = 0; k < slots; k++) {
                IloConstraint isLastValid = (k == slots - 1)
                        ? cp.gt(nb[i][k], 0)
                        : cp.and(cp.gt(nb[i][k], 0), cp.eq(nb[i][k + 1], 0));
                cp.add(cp.ifThen(isLastValid, cp.gt(nb[i][k], i + 1)));
            }
        }
    }

    // =========================================================================
    //  SEARCH
    // =========================================================================

    /**
     * Runs the search to exhaustion, counting solutions and passing each one to {@code listener}.
     *
     * <p>The reported figures cover CP Optimizer's generation work only:
     * <ul>
     *   <li>time: summed over the solver calls ({@code startNewSearch}, every {@code next},
     *       {@code endSearch}); the listener's work between calls is not counted;</li>
     *   <li>memory: CP Optimizer's own peak memory, which lives outside the Java heap,
     *       so graphs the listener keeps don't count towards it.</li>
     * </ul>
     */
    private static Result enumerate(IloCP cp, IloIntVar[][] vars, SolutionListener listener) throws IloException {
        long cpNanos = 0;
        int count = 0;

        long t = System.nanoTime();
        cp.startNewSearch();
        cpNanos += System.nanoTime() - t;

        while (true) {
            t = System.nanoTime();
            boolean found = cp.next();
            cpNanos += System.nanoTime() - t;
            if (!found) break;

            count++;
            if (listener != null) {
                listener.onSolution(() -> values(cp, vars));
            }
        }

        t = System.nanoTime();
        cp.endSearch();
        cpNanos += System.nanoTime() - t;

        return new Result(
                count,
                cpNanos / 1_000_000,
                cp.getInfo(IloCP.IntInfo.PeakMemoryUsage));
    }

    /** The current solution's value of every variable in {@code vars}. */
    private static int[][] values(IloCP cp, IloIntVar[][] vars) {
        try {
            return readValues(cp, vars);
        } catch (IloException e) {
            throw new RuntimeException(e);
        }
    }

    private static int[][] readValues(IloCP cp, IloIntVar[][] vars) throws IloException {
        int[][] out = new int[vars.length][];
        for (int i = 0; i < vars.length; i++) {
            out[i] = new int[vars[i].length];
            for (int k = 0; k < vars[i].length; k++) {
                out[i][k] = (int) Math.round(cp.getValue(vars[i][k]));
            }
        }
        return out;
    }

    /** Returns {@code row} without columns {@code skip1} and {@code skip2}, in reverse order. */
    private static IloIntExpr[] reversedWithout(IloIntExpr[] row, int skip1, int skip2) {
        IloIntExpr[] out = new IloIntExpr[row.length - 2];
        int k = 0;
        for (int col = row.length - 1; col >= 0; col--) {
            if (col != skip1 && col != skip2) {
                out[k++] = row[col];
            }
        }
        return out;
    }
}
