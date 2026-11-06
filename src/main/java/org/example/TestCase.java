package org.example;

/** A benchmark instance G_n: all graphs on n vertices. */
public record TestCase(int vertices) {

    /** Short name, e.g. G_8 → {@code "N8"}. */
    public String name() {
        return "N" + vertices;
    }
}
