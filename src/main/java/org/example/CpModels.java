package org.example;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloIntExpr;
import ilog.concert.IloIntVar;
import ilog.cp.IloCP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CP Optimizer models that enumerate all graphs on N vertices (any degrees).
 *
 * <p>Two encodings of a graph:
 * <ul>
 *   <li>ADJACENCY: an N×N 0/1 matrix A, {@code A[i][j] = 1} iff i and j are adjacent.</li>
 *   <li>NEIGHBORS: for each vertex i, its neighbors (labels 1..N) sorted in N-1 slots,
 *       left-padded with 0, e.g. {@code M[i] = [0, 0, 2, 5]}.</li>
 * </ul>
 * Both use OptRevLex symmetry breaking; connectivity constraints can be added on top.
 */
public final class CpModels {

    public enum Encoding { ADJACENCY, NEIGHBORS }

    public enum Connectivity {
        /** No connectivity constraint: every graph is enumerated. */
        NONE,
        /** Upper off-diagonal: every vertex i < N-1 has a neighbor with a larger label. */
        UPPER_OFF_DIAGONAL,
        /** Neighbors encoding only: the same idea, stated on the last non-zero slot. */
        LAST_VALID_SLOT
    }

    private CpModels() {}

    /**
     * Builds the model and enumerates every solution.
     *
     * @param maxKept how many of the first solutions to return in {@link Result#graphs()}
     */
    public static Result solve(int n, Encoding encoding, Connectivity connectivity, int maxKept) {
        IloCP cp = null;
        try {
            cp = new IloCP();
            cp.setParameter(IloCP.IntParam.LogVerbosity, IloCP.ParameterValues.Quiet);
            cp.setParameter(IloCP.IntParam.SearchType, IloCP.ParameterValues.DepthFirst);
            cp.setParameter(IloCP.IntParam.DefaultInferenceLevel, IloCP.ParameterValues.Low);
            cp.setParameter(IloCP.IntParam.MemoryDisplay, 0);

            // Posting order (base model, OptRevLex, connectivity) affects the search statistics.
            IloIntVar[][] vars = (encoding == Encoding.ADJACENCY)
                    ? adjacencyModel(cp, n, connectivity)
                    : neighborsModel(cp, n, connectivity);
            return enumerate(cp, vars, maxKept);
        } catch (IloException e) {
            throw new RuntimeException(e);
        } finally {
            if (cp != null) cp.end();   // free the solver's native memory
        }
    }

    // =========================================================================
    //  ADJACENCY MODEL
    // =========================================================================

    private static IloIntVar[][] adjacencyModel(IloCP cp, int N, Connectivity connectivity)
            throws IloException {
        IloIntVar[][] A = new IloIntVar[N][];
        for (int i = 0; i < N; i++)
            A[i] = cp.intVarArray(N, 0, 1);

        for (int i = 0; i < N; i++) {
            cp.add(cp.eq(A[i][i], 0));                       // no self-loops
            for (int j = i + 1; j < N; j++)
                cp.add(cp.eq(A[i][j], A[j][i]));             // symmetric
        }

        addOptRevLexAdj(cp, A);

        switch (connectivity) {
            case NONE -> {}
            case UPPER_OFF_DIAGONAL -> addConnectivityAdj(cp, A);
            case LAST_VALID_SLOT -> throw new IllegalArgumentException(
                    "LAST_VALID_SLOT applies to the neighbors encoding only");
        }
        return A;
    }

    /** Adjacency model — OptRevLex ordering on ALL pairs i < j. */
    private static void addOptRevLexAdj(IloCP cp, IloIntVar[][] A) throws IloException {
        int N = A.length;
        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                cp.add(cp.lexicographic(
                        reverse(arrayNew(A[i], i, j)),
                        reverse(arrayNew(A[j], i, j))));
            }
        }
    }

    /** Every vertex i < N-1 has at least one neighbor j > i. */
    private static void addConnectivityAdj(IloCP cp, IloIntVar[][] A) throws IloException {
        int N = A.length;
        for (int i = 0; i < N - 1; i++)
            cp.add(cp.gt(cp.sum(Arrays.copyOfRange(A[i], i + 1, N)), 0));
    }

    // =========================================================================
    //  NEIGHBORS MODEL
    // =========================================================================

    private static IloIntVar[][] neighborsModel(IloCP cp, int N, Connectivity connectivity)
            throws IloException {
        int slots = N - 1;   // the largest possible degree
        IloIntVar[][] M = new IloIntVar[N][];
        for (int i = 0; i < N; i++) {
            M[i] = cp.intVarArray(slots, 0, N);
            for (int k = 0; k < slots; k++)
                cp.add(cp.neq(M[i][k], i + 1));              // no self-loops
            for (int k = 0; k < slots - 1; k++)              // zeros first, then increasing
                cp.add(cp.or(cp.eq(M[i][k], 0), cp.lt(M[i][k], M[i][k + 1])));
        }

        for (int u = 0; u < N; u++)                          // u ∈ M[v]  ⇔  v ∈ M[u]
            for (int v = u + 1; v < N; v++)
                cp.add(cp.eq(cp.count(M[u], v + 1), cp.count(M[v], u + 1)));

        addOptRevLexNei(cp, M);

        switch (connectivity) {
            case NONE -> {}
            case UPPER_OFF_DIAGONAL -> addConnectivityNei(cp, M);
            case LAST_VALID_SLOT -> addConnectivityNeiLastValidSlot(cp, M);
        }
        return M;
    }

    /** Neighbors model — OptRevLex ordering on ALL pairs i < j, as in the paper. */
    private static void addOptRevLexNei(IloCP cp, IloIntVar[][] M) throws IloException {
        int N = M.length;
        // C[i][v] = 1 iff vertex v+1 is a neighbor of i: the adjacency matrix, as expressions
        IloIntExpr[][] C = new IloIntExpr[N][N];
        for (int i = 0; i < N; i++)
            for (int v = 0; v < N; v++)
                C[i][v] = (v == i) ? cp.constant(0) : cp.count(M[i], v + 1);

        for (int i = 0; i < N - 1; i++) {
            for (int j = i + 1; j < N; j++) {
                // N_i ⪯opt N_j : compare rows i and j, columns i and j excluded,
                // right-most column most significant (hence reverse)
                cp.add(cp.lexicographic(
                        reverse(arrayNew(C[i], i, j)),
                        reverse(arrayNew(C[j], i, j))));
            }
        }
    }

    /** Every vertex i < N-1 has a neighbor j > i: its largest neighbor (last slot) is above i. */
    private static void addConnectivityNei(IloCP cp, IloIntVar[][] M) throws IloException {
        int N = M.length;
        int last = M[0].length - 1;
        for (int i = 0; i < N - 1; i++)
            cp.add(cp.gt(M[i][last], i + 1));
    }

    /**
     * Same as {@link #addConnectivityNei}, stated on the last valid slot: vertex i < N-1 has
     * at least one neighbor, and the last non-zero slot (followed only by zeros) is above i.
     */
    private static void addConnectivityNeiLastValidSlot(IloCP cp, IloIntVar[][] M) throws IloException {
        int N = M.length;
        int slots = M[0].length;
        for (int i = 0; i < N - 1; i++) {
            cp.add(cp.gt(cp.sum(M[i]), 0));
            for (int k = 0; k < slots; k++) {
                IloConstraint isLastValid = (k == slots - 1)
                        ? cp.gt(M[i][k], 0)
                        : cp.and(cp.gt(M[i][k], 0), cp.eq(M[i][k + 1], 0));
                cp.add(cp.ifThen(isLastValid, cp.gt(M[i][k], i + 1)));
            }
        }
    }

    // =========================================================================
    //  SEARCH
    // =========================================================================

    /**
     * Runs the search to exhaustion. Time counts the solver calls only (not copying out
     * the kept solutions); memory is CP Optimizer's own peak, outside the Java heap.
     */
    private static Result enumerate(IloCP cp, IloIntVar[][] vars, int maxKept) throws IloException {
        List<int[][]> kept = new ArrayList<>();
        int count = 0;
        long cpNanos = 0;

        long t = System.nanoTime();
        cp.startNewSearch();
        while (cp.next()) {
            cpNanos += System.nanoTime() - t;
            count++;
            if (kept.size() < maxKept) kept.add(values(cp, vars));
            t = System.nanoTime();
        }
        cp.endSearch();
        cpNanos += System.nanoTime() - t;

        return new Result(count, cpNanos / 1_000_000, cp.getInfo(IloCP.IntInfo.PeakMemoryUsage), kept);
    }

    /** The current solution's values. */
    private static int[][] values(IloCP cp, IloIntVar[][] vars) throws IloException {
        int[][] out = new int[vars.length][];
        for (int i = 0; i < vars.length; i++) {
            out[i] = new int[vars[i].length];
            for (int k = 0; k < vars[i].length; k++)
                out[i][k] = (int) Math.round(cp.getValue(vars[i][k]));
        }
        return out;
    }

    // =========================================================================
    //  ARRAY HELPERS
    // =========================================================================

    /** {@code row} without columns {@code i} and {@code j}. */
    private static IloIntExpr[] arrayNew(IloIntExpr[] row, int i, int j) {
        IloIntExpr[] out = new IloIntExpr[row.length - 2];
        int k = 0;
        for (int col = 0; col < row.length; col++)
            if (col != i && col != j) out[k++] = row[col];
        return out;
    }

    /** {@code a} in reverse order. */
    private static IloIntExpr[] reverse(IloIntExpr[] a) {
        IloIntExpr[] out = new IloIntExpr[a.length];
        for (int k = 0; k < a.length; k++)
            out[k] = a[a.length - 1 - k];
        return out;
    }
}
