package org.example;

import java.util.Arrays;
import java.util.List;

/**
 * Benchmark instances used in the paper:
 *
 * <p>"RevLex Ordering and Upper Off-Diagonal Connectivity Constraints:
 * A Synergistic Approach for Connected Non-Isomorphic Graph Enumeration"
 *
 * <p>Each instance is a vertex count N: all graphs on N vertices, whatever their degrees.
 * For reference, the numbers of non-isomorphic graphs (connected ones in brackets) are
 * N=4: 11 (6), 5: 34 (21), 6: 156 (112), 7: 1,044 (853), 8: 12,346 (11,117),
 * 9: 274,668 (261,080), 10: 12,005,168 (11,716,571).
 * OptRevLex is not complete symmetry breaking, so the counts found are somewhat higher.
 *
 * <p>Edit {@link #GROUPS} to choose which instances are run.
 */
public final class PaperBenchmarkData {

    /** A block of the results table. */
    public record Group(String title, List<TestCase> instances) {}

    public static final List<Group> GROUPS = List.of(
            allGraphs(6)   // the neighbors encoding takes ~2 min at N=7; N≥8 is much longer
    );

    private PaperBenchmarkData() {}

    /** The all-graphs instance for each given vertex count N. */
    private static Group allGraphs(int... vertexCounts) {
        List<TestCase> instances = Arrays.stream(vertexCounts)
                .mapToObj(TestCase::new)
                .toList();
        return new Group("All Graphs on N vertices", instances);
    }
}
