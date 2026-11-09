package org.example;

/** Outcome of one solver run: solution count, time and search statistics. */
public class Result {
    /** Number of solutions (graphs) found. */
    public int    count;
    /** Wall-clock search time in milliseconds. */
    public double cpu;
    /** Number of failures during search. */
    public long   fails;
    /** Number of branches explored. */
    public long   branches;
    /** Number of choice points created. */
    public long   choicePoints;
    /** Number of constraints in the model. */
    public long   constraints;
    /** Peak memory used by the solver, in bytes. */
    public long   peakMemory;

    public Result(int count, double cpu,
                  long fails, long branches,
                  long choicePoints, long constraints, long peakMemory) {
        this.count        = count;
        this.cpu          = cpu;
        this.fails        = fails;
        this.branches     = branches;
        this.choicePoints = choicePoints;
        this.constraints  = constraints;
        this.peakMemory   = peakMemory;
    }

    @Override
    public String toString() {
        return String.format(
                "Result{count=%d, cpu=%.1fms, fails=%d, branches=%d, choicePoints=%d, constraints=%d, peakMemory=%d}",
                count, cpu, fails, branches, choicePoints, constraints, peakMemory
        );
    }
}
