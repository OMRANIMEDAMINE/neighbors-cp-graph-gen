package org.example;

/** One benchmark instance: a name and the required degree of each vertex. */
public class TestCase {
    /** Instance name, e.g. "K8_3" for K_8(3). */
    String name;
    /** Degree of each vertex; its length is the number of vertices. */
    int[] degrees;
    /** Human-readable description (documentation only). */
    String description;

    TestCase(String name, int[] degrees, String description) {
        this.name = name;
        this.degrees = degrees;
        this.description = description;
    }

    /** Number of vertices of the graph. */
    int vertices() {
        return degrees.length;
    }
}
