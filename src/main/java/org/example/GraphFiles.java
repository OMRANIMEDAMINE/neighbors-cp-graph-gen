package org.example;

import org.example.CpModels.Encoding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Saves the graphs of one run to disk:
 * <ul>
 *   <li>{@code <title>.txt}: each graph as an adjacency matrix or as neighbor lists,
 *       matching the encoding that produced it.</li>
 *   <li>{@code <title>.pdf}: a drawing of each graph, vertices on a circle.</li>
 * </ul>
 * The title names the instance and the configuration, e.g. {@code N8_OptRevLexAdj}.
 * {@code graphs} may be only the first part of the {@code found} graphs.
 */
public final class GraphFiles {

    private GraphFiles() {}

    public static Path writeTxt(Path dir, String title, int n, Encoding encoding,
                                int found, List<int[][]> graphs) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        sb.append("n = ").append(n)
          .append(", graphs found = ").append(found)
          .append(", written = ").append(graphs.size())
          .append(", format = ").append(encoding == Encoding.ADJACENCY ? "adjacency matrix" : "neighbor lists")
          .append('\n');

        for (int g = 0; g < graphs.size(); g++) {
            sb.append('\n').append("Graph ").append(g + 1).append('\n');
            int[][] rows = graphs.get(g);
            for (int i = 0; i < rows.length; i++) {
                if (encoding == Encoding.NEIGHBORS) {
                    sb.append(i + 1).append(": ");
                }
                for (int k = 0; k < rows[i].length; k++) {   // all slots, 0 = empty
                    if (k > 0) sb.append(' ');
                    sb.append(rows[i][k]);
                }
                sb.append('\n');
            }
        }
        return write(dir.resolve(title + ".txt"), sb.toString().getBytes());
    }

    public static Path writePdf(Path dir, String title, int n, Encoding encoding,
                                int found, List<int[][]> graphs) {
        List<boolean[][]> adjacency = graphs.stream()
                .map(g -> toAdjacency(g, n, encoding))
                .toList();
        return write(dir.resolve(title + ".pdf"), GraphPdf.render(title, found, adjacency));
    }

    /** Converts one solution, in either encoding, to a boolean adjacency matrix. */
    private static boolean[][] toAdjacency(int[][] solution, int n, Encoding encoding) {
        boolean[][] adj = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < solution[i].length; k++) {
                if (encoding == Encoding.ADJACENCY) {
                    adj[i][k] = solution[i][k] == 1;
                } else if (solution[i][k] > 0) {   // 0 is a padding slot
                    adj[i][solution[i][k] - 1] = true;   // neighbor labels are 1-based
                }
            }
        }
        return adj;
    }

    private static Path write(Path file, byte[] content) {
        try {
            Files.createDirectories(file.getParent());
            return Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
