package org.example;

/**
 * Outcome of one solver run.
 *
 * @param count       number of solutions found
 * @param timeMs      wall-clock search time in milliseconds
 * @param memoryBytes peak memory used by CP Optimizer, in bytes
 */
public record Result(int count, long timeMs, long memoryBytes) {}
