package org.example;

/**
 * Outcome of one solver run.
 *
 * @param count      number of solutions (graphs) found
 * @param timeMs     wall-clock search time in milliseconds
 * @param peakMemory peak memory used by the solver, in bytes
 */
public record Result(int count, long timeMs, long peakMemory) {}
