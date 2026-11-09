package org.example;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal PDF writer that draws graphs, with no external library.
 *
 * <p>Each A4 page holds a grid of {@value #COLS}×{@value #ROWS} graphs. The vertices of
 * each graph sit on a circle, labelled 1..n, with straight-line edges.
 */
final class GraphPdf {

    private static final double PAGE_W = 595, PAGE_H = 842;   // A4 in points
    private static final double MARGIN = 36, HEADER = 30;
    private static final int COLS = 2, ROWS = 3;
    private static final int PER_PAGE = COLS * ROWS;

    private GraphPdf() {}

    /** Renders the graphs (given as adjacency matrices), out of {@code found} in total, into a PDF document. */
    static byte[] render(String title, int found, List<boolean[][]> graphs) {
        List<String> pages = new ArrayList<>();
        int pageCount = Math.max(1, (graphs.size() + PER_PAGE - 1) / PER_PAGE);
        for (int p = 0; p < pageCount; p++) {
            int from = p * PER_PAGE;
            int to = Math.min(graphs.size(), from + PER_PAGE);
            pages.add(page(title, found, p + 1, pageCount, graphs, from, to));
        }
        return assemble(pages);
    }

    // =========================================================================
    //  DRAWING
    // =========================================================================

    private static String page(String title, int found, int pageNo, int pageCount,
                               List<boolean[][]> graphs, int from, int to) {
        StringBuilder s = new StringBuilder();
        text(s, MARGIN, PAGE_H - MARGIN, 14,
                title + "   (" + graphs.size() + " of " + found + " graphs, page " + pageNo + "/" + pageCount + ")");

        double cellW = (PAGE_W - 2 * MARGIN) / COLS;
        double cellH = (PAGE_H - 2 * MARGIN - HEADER) / ROWS;
        for (int g = from; g < to; g++) {
            int slot = g - from;
            double left = MARGIN + (slot % COLS) * cellW;
            double top = PAGE_H - MARGIN - HEADER - (slot / COLS) * cellH;
            text(s, left + 6, top - 14, 10, "Graph " + (g + 1));
            graph(s, graphs.get(g), left + cellW / 2, top - cellH / 2 - 6,
                    Math.min(cellW, cellH) / 2 - 30);
        }
        return s.toString();
    }

    private static void graph(StringBuilder s, boolean[][] adj, double cx, double cy, double radius) {
        int n = adj.length;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            double angle = Math.PI / 2 - 2 * Math.PI * i / n;   // vertex 1 at the top, clockwise
            x[i] = cx + radius * Math.cos(angle);
            y[i] = cy + radius * Math.sin(angle);
        }

        s.append("0.8 w 0 0 0 RG\n");
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (adj[i][j]) {
                    s.append(f(x[i])).append(' ').append(f(y[i])).append(" m ")
                     .append(f(x[j])).append(' ').append(f(y[j])).append(" l S\n");
                }
            }
        }

        double r = 8;
        int fontSize = n >= 10 ? 7 : 8;
        for (int i = 0; i < n; i++) {
            s.append("1 1 1 rg\n");
            circle(s, x[i], y[i], r);
            s.append("B\n0 0 0 rg\n");
            String label = Integer.toString(i + 1);
            double textW = 0.556 * fontSize * label.length();   // Helvetica digit width
            text(s, x[i] - textW / 2, y[i] - fontSize * 0.35, fontSize, label);
        }
    }

    /** Circle path from four Bézier arcs. */
    private static void circle(StringBuilder s, double cx, double cy, double r) {
        double k = 0.5523 * r;
        s.append(f(cx + r)).append(' ').append(f(cy)).append(" m\n");
        curve(s, cx + r, cy + k, cx + k, cy + r, cx, cy + r);
        curve(s, cx - k, cy + r, cx - r, cy + k, cx - r, cy);
        curve(s, cx - r, cy - k, cx - k, cy - r, cx, cy - r);
        curve(s, cx + k, cy - r, cx + r, cy - k, cx + r, cy);
    }

    private static void curve(StringBuilder s, double... p) {
        for (double v : p) s.append(f(v)).append(' ');
        s.append("c\n");
    }

    private static void text(StringBuilder s, double x, double y, int size, String str) {
        String escaped = str.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        s.append("BT /F1 ").append(size).append(" Tf ")
         .append(f(x)).append(' ').append(f(y)).append(" Td (").append(escaped).append(") Tj ET\n");
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // =========================================================================
    //  PDF FILE STRUCTURE
    // =========================================================================

    /**
     * Objects: 1 = catalog, 2 = page tree, 3 = font,
     * then for each page a page object followed by its content stream.
     */
    private static byte[] assemble(List<String> contents) {
        List<String> objects = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < contents.size(); p++) {
            kids.append(4 + 2 * p).append(" 0 R ");
        }
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        objects.add("<< /Type /Pages /Kids [" + kids + "] /Count " + contents.size() + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        for (int p = 0; p < contents.size(); p++) {
            String content = contents.get(p);
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) PAGE_W + " " + (int) PAGE_H + "]"
                    + " /Resources << /Font << /F1 3 0 R >> >> /Contents " + (5 + 2 * p) + " 0 R >>");
            objects.add("<< /Length " + content.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n"
                    + content + "endstream");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        put(out, "%PDF-1.4\n");
        long[] offsets = new long[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = out.size();
            put(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }
        long xref = out.size();
        StringBuilder tail = new StringBuilder();
        tail.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (long off : offsets) {
            tail.append(String.format("%010d 00000 n \n", off));
        }
        tail.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n")
            .append("startxref\n").append(xref).append("\n%%EOF\n");
        put(out, tail.toString());
        return out.toByteArray();
    }

    private static void put(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }
}
