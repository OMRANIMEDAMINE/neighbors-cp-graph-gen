package org.example;

import ilog.concert.IloException;
import ilog.concert.IloIntExpr;
import ilog.concert.IloIntVar;
import ilog.cp.IloCP;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * CP Optimizer models that enumerate the d-regular graphs on n vertices.
 *
 * <p>Two encodings of the same graph are available:
 * <ul>
 *   <li>{@link Encoding#ADJACENCY}: an n×n 0/1 adjacency matrix.</li>
 *   <li>{@link Encoding#NEIGHBORS}: for each vertex, the sorted list of its d neighbors
 *       (vertex labels are 1-based).</li>
 * </ul>
 *
 * <p>Both encodings use the OptRevLex symmetry-breaking constraints. The upper
 * off-diagonal connectivity constraints can be added on top of them.
 */
public final class CpModels {

    public enum Encoding { ADJACENCY, NEIGHBORS }

    /**
     * Notified of every solution found. {@code values.get()} reads the solution:
     * an n×n adjacency matrix for {@link Encoding#ADJACENCY}, n×d neighbor lists
     * for {@link Encoding#NEIGHBORS}. A listener that doesn't need a solution can
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
     * @param d         degree of every vertex
     * @param encoding  graph encoding to use
     * @param connected whether to add the upper off-diagonal connectivity constraints
     * @param listener  notified of each solution, or {@code null}
     */
    public static Result solve(int n, int d, Encoding encoding, boolean connected, SolutionListener listener) {
        try {
            IloCP cp = new IloCP();
            configureSolver(cp);

            // Note: constraint posting order affects CP Optimizer's search statistics.
            if (encoding == Encoding.ADJACENCY) {
                IloIntVar[][] a = buildAdjacencyModel(cp, n, d);
                addOptRevLex(cp, a);
                if (connected) addAdjacencyConnectivity(cp, a);
                return enumerate(cp, a, listener);
            } else {
                IloIntVar[][] nb = buildNeighborsModel(cp, n, d);
                addOptRevLex(cp, adjacencyFromNeighbors(cp, nb));
                if (connected) addNeighborsConnectivity(cp, nb);
                return enumerate(cp, nb, listener);
            }
        } catch (IloException e) {
            throw new RuntimeException(e);
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
     * Posts: no self-loops, every row sums to d, and the matrix is symmetric.
     */
    private static IloIntVar[][] buildAdjacencyModel(IloCP cp, int n, int d) throws IloException {
        IloIntVar[][] a = new IloIntVar[n][];
        for (int i = 0; i < n; i++) {
            a[i] = cp.intVarArray(n, 0, 1);
        }
        for (int i = 0; i < n; i++) {
            cp.add(cp.eq(a[i][i], 0));
        }
        for (int i = 0; i < n; i++) {
            cp.addEq(cp.sum(a[i]), d);
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                cp.add(cp.eq(a[i][j], a[j][i]));
            }
        }
        return a;
    }

    /**
     * Neighbors encoding: {@code N[i]} holds the d neighbors of vertex i+1
     * (values in 1..n) in strictly increasing order.
     * Posts: no self-loops, sorted lists, and u ∈ N[v] ⇔ v ∈ N[u].
     */
    private static IloIntVar[][] buildNeighborsModel(IloCP cp, int n, int d) throws IloException {
        IloIntVar[][] nb = new IloIntVar[n][];
        for (int i = 0; i < n; i++) {
            nb[i] = cp.intVarArray(d, 1, n);
            for (int k = 0; k < d; k++) {
                cp.add(cp.neq(nb[i][k], i + 1));
            }
            for (int k = 0; k < d - 1; k++) {
                cp.add(cp.lt(nb[i][k], nb[i][k + 1]));
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

    // =========================================================================
    //  SEARCH
    // =========================================================================

    /** Runs the search to exhaustion, counting solutions and passing each one to {@code listener}. */
    private static Result enumerate(IloCP cp, IloIntVar[][] vars, SolutionListener listener) throws IloException {
        long start = System.currentTimeMillis();
        cp.startNewSearch();
        int count = 0;
        while (cp.next()) {
            count++;
            if (listener != null) {
                listener.onSolution(() -> values(cp, vars));
            }
        }
        cp.endSearch();
        long elapsedMs = System.currentTimeMillis() - start;

        return new Result(
                count,
                elapsedMs,
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
