package org.example;

import java.util.Arrays;
import java.util.List;

/**
 * Benchmark instances used in the paper:
 *
 * <p>"RevLex Ordering and Upper Off-Diagonal Connectivity Constraints:
 * A Synergistic Approach for Connected Non-Isomorphic Graph Enumeration"
 *
 * <p>All instances are d-regular graphs K_n(d). The benchmark is restricted to
 * d &lt; n/2: complementation maps K_n(d) one-to-one onto K_n(n−1−d).
 * For odd d, n must be even (n·d must be even).
 *
 * <p>Edit {@link #GROUPS} to choose which instances are run.
 */
public final class PaperBenchmarkData {

    /** A degree family, printed as one block of the results table. */
    public record Group(String title, List<TestCase> instances) {}

    public static final List<Group> GROUPS = List.of(
            //family(2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            //family(3, 4, 6, 8, 10, 12),
            //family(4, 7, 8, 9, 10, 11, 12),
            family(5, 8, 10)
    );

    private PaperBenchmarkData() {}

    /** The d-regular instances for each given vertex count n. */
    private static Group family(int degree, int... vertexCounts) {
        List<TestCase> instances = Arrays.stream(vertexCounts)
                .mapToObj(n -> new TestCase(n, degree))
                .toList();
        return new Group(degree + "-Regular Graphs  K_n(" + degree + ")", instances);
    }
}
