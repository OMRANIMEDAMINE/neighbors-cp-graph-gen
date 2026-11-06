package org.example;

/** A benchmark instance K_n(d): the d-regular graphs on n vertices. */
public record TestCase(int vertices, int degree) {

    /** Short name, e.g. K_8(3) → {@code "K8_3"}. */
    public String name() {
        return "K" + vertices + "_" + degree;
    }
}
