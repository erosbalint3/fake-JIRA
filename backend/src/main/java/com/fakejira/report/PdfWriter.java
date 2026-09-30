package com.fakejira.report;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes simple text-and-table PDF documents (A4 landscape, Helvetica) without extra libraries. Text outside
 * Latin-1 is simplified (accents kept where the font has them).
 */
public final class PdfWriter {

    static final float WIDTH = 842;
    static final float HEIGHT = 595;
    static final float MARGIN = 36;

    /** A heading, a paragraph, or a table (first row is the header; {@code widths} are relative). */
    public sealed interface Block permits Heading, Paragraph, Table {
    }

    public record Heading(String text) implements Block {
    }

    public record Paragraph(String text) implements Block {
    }

    public record Table(List<List<String>> rows, float[] widths) implements Block {
    }

    private final List<StringBuilder> pages = new ArrayList<>();
    private StringBuilder page;
    private float y;

    private PdfWriter() {
    }

    public static byte[] write(String title, List<Block> blocks) {
        PdfWriter writer = new PdfWriter();
        writer.newPage();
        writer.text(title, MARGIN, 18, true);
        writer.y -= 28;
        for (Block block : blocks) {
            if (block instanceof Heading heading) {
                writer.ensure(40);
                writer.y -= 8;
                writer.text(heading.text(), MARGIN, 13, true);
                writer.y -= 20;
            } else if (block instanceof Paragraph paragraph) {
                for (String line : wrap(paragraph.text(), (WIDTH - 2 * MARGIN), 10)) {
                    writer.ensure(14);
                    writer.text(line, MARGIN, 10, false);
                    writer.y -= 14;
                }
                writer.y -= 4;
            } else if (block instanceof Table table) {
                writer.table(table);
            }
        }
        return writer.finish();
    }

    private void table(Table table) {
        if (table.rows().isEmpty()) {
            return;
        }
        float total = 0;
        int columns = table.rows().get(0).size();
        float[] widths = table.widths() != null && table.widths().length == columns ? table.widths() : new float[columns];
        for (int i = 0; i < columns; i++) {
            if (widths[i] <= 0) widths[i] = 1;
            total += widths[i];
        }
        float available = WIDTH - 2 * MARGIN;
        List<String> header = table.rows().get(0);
        for (int r = 0; r < table.rows().size(); r++) {
            boolean isHeader = r == 0;
            if (!isHeader && y < MARGIN + 30) {
                newPage();
                row(header, widths, total, available, true);
            }
            row(table.rows().get(r), widths, total, available, isHeader);
        }
        y -= 8;
    }

    private void row(List<String> cells, float[] widths, float total, float available, boolean bold) {
        ensure(16);
        if (bold) {
            page.append(String.format(java.util.Locale.ROOT, "0.93 0.94 0.97 rg %.1f %.1f %.1f 15 re f 0 0 0 rg%n",
                    MARGIN, y - 4, available));
        }
        float x = MARGIN;
        for (int i = 0; i < cells.size() && i < widths.length; i++) {
            float w = widths[i] / total * available;
            text(fit(cells.get(i) == null ? "" : cells.get(i), w - 6, 9), x + 3, 9, bold);
            x += w;
        }
        y -= 15;
        if (!bold) {
            page.append(String.format(java.util.Locale.ROOT, "0.85 0.86 0.9 RG 0.5 w %.1f %.1f m %.1f %.1f l S%n",
                    MARGIN, y + 11, MARGIN + available, y + 11));
        }
    }

    private void ensure(float needed) {
        if (y - needed < MARGIN) {
            newPage();
        }
    }

    private void newPage() {
        page = new StringBuilder();
        pages.add(page);
        y = HEIGHT - MARGIN - 10;
    }

    private void text(String text, float x, float size, boolean bold) {
        page.append("BT /").append(bold ? "F2" : "F1").append(' ').append(size).append(" Tf ")
                .append(String.format(java.util.Locale.ROOT, "%.1f %.1f", x, y)).append(" Td (")
                .append(escape(latin1(text))).append(") Tj ET\n");
    }

    /** Rough Helvetica metrics: average glyph width of about half the font size. */
    static String fit(String text, float width, float size) {
        int max = Math.max(1, (int) (width / (size * 0.5)));
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    static List<String> wrap(String text, float width, float size) {
        int max = Math.max(10, (int) (width / (size * 0.5)));
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.length() + word.length() + 1 > max && line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** Maps text into WinAnsi/Latin-1: ő → ö, ű → ü, other accents stripped, the rest as '?'. */
    static String latin1(String text) {
        StringBuilder out = new StringBuilder();
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case 'ő' -> out.append('ö');
                case 'Ő' -> out.append('Ö');
                case 'ű' -> out.append('ü');
                case 'Ű' -> out.append('Ü');
                case '…' -> out.append('\u0085');
                case '–', '—' -> out.append('-');
                case '“', '”' -> out.append('"');
                case '‘', '’' -> out.append('\'');
                case '·' -> out.append('\u00B7');
                default -> {
                    if (ch < 256) {
                        out.append(ch);
                    } else {
                        String base = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
                        out.append(base.length() == 1 && base.charAt(0) < 256 ? base : "?");
                    }
                }
            }
        }
        return out.toString();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").replace("\r", "").replace("\n", " ");
    }

    private byte[] finish() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n");
        int pageCount = pages.size();
        // Objects: 1 catalog, 2 pages, 3 font, 4 bold font, then page + content pairs.
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            kids.append(5 + i * 2).append(" 0 R ");
        }
        offsets.add(out.size());
        write(out, "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n");
        offsets.add(out.size());
        write(out, "2 0 obj << /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >> endobj\n");
        offsets.add(out.size());
        write(out, "3 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >> endobj\n");
        offsets.add(out.size());
        write(out, "4 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >> endobj\n");
        for (int i = 0; i < pageCount; i++) {
            int pageObject = 5 + i * 2;
            String footer = String.format(java.util.Locale.ROOT, "BT /F1 8 Tf %.1f %.1f Td (%d / %d) Tj ET\n",
                    WIDTH - MARGIN - 30, MARGIN / 2, i + 1, pageCount);
            byte[] content = (pages.get(i) + footer).getBytes(StandardCharsets.ISO_8859_1);
            offsets.add(out.size());
            write(out, pageObject + " 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) WIDTH + " " + (int) HEIGHT
                    + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (pageObject + 1) + " 0 R >> endobj\n");
            offsets.add(out.size());
            write(out, (pageObject + 1) + " 0 obj << /Length " + content.length + " >> stream\n");
            out.writeBytes(content);
            write(out, "\nendstream endobj\n");
        }
        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            table.append(String.format("%010d 00000 n \n", offset));
        }
        table.append("trailer << /Size ").append(offsets.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, table.toString());
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    }
}
