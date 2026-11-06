package org.example;

/**
 * Outcome of one solver run.
 *
 * @param count       number of solutions found
 * @param timeMs      time spent inside CP Optimizer during the search, in milliseconds
 * @param memoryBytes peak memory used by CP Optimizer itself (not the Java heap), in bytes
 */
public record Result(int count, long timeMs, long memoryBytes) {}
