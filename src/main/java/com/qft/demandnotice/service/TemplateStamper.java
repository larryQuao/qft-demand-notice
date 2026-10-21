package com.qft.demandnotice.service;

import com.qft.demandnotice.model.DemandNoticeData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Replaces token placeholders (e.g. {@code {{AMOUNT}}}) inside a converted
 * Publisher PDF with the variable field values, keeping the rest of the
 * layout untouched.
 *
 * <p>Supported tokens:</p>
 * <ul>
 *   <li>{@code {{LETTER_DATE}}} - e.g. "24th September, 2026"</li>
 *   <li>{@code {{PERIOD}}} - e.g. "September, 2026"</li>
 *   <li>{@code {{AMOUNT}}} - e.g. "GHS 1,389.00"</li>
 *   <li>{@code {{DEADLINE}}} - e.g. "14th October, 2026"</li>
 * </ul>
 *
 * <p>Tokens that are not present in the template are reported as unreplaced;
 * a template without any tokens is passed through unchanged (plain
 * .pub-to-PDF conversion).</p>
 */
public final class TemplateStamper {

    private static final PDType1Font STAMP_FONT =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    /** Token (exact text) to value resolver. Insertion order = display order. */
    public static final Map<String, Function<DemandNoticeData, String>> TOKENS =
            new LinkedHashMap<>();

    static {
        TOKENS.put("{{LETTER_DATE}}", d -> DateFormatter.formatLetterDate(d.letterDate()));
        TOKENS.put("{{PERIOD}}", d -> DateFormatter.formatContributionPeriod(d.contributionMonth()));
        TOKENS.put("{{AMOUNT}}", d -> AmountFormatter.format(d.amount()));
        TOKENS.put("{{DEADLINE}}", d -> DateFormatter.formatLetterDate(d.deadline()));
    }

    private TemplateStamper() {
    }

    /**
     * Result of stamping a template.
     *
     * @param pdfBytes         final PDF with tokens replaced
     * @param replacedTokens   tokens that were found and replaced (display names)
     * @param unreplacedTokens tokens with no occurrence left in the template
     */
    public record StampOutcome(byte[] pdfBytes, List<String> replacedTokens,
                               List<String> unreplacedTokens) {
    }

    /** Stamps an in-memory template PDF and returns the resulting bytes. */
    public static StampOutcome stamp(byte[] templatePdf, DemandNoticeData data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(templatePdf)) {
            StampOutcome outcome = stamp(doc, data);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return new StampOutcome(baos.toByteArray(), outcome.replacedTokens(),
                    outcome.unreplacedTokens());
        }
    }

    /** Stamps a loaded document in place (caller saves). */
    static StampOutcome stamp(PDDocument doc, DemandNoticeData data) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        TOKENS.forEach((token, resolver) -> values.put(token, resolver.apply(data)));

        Map<String, List<Occurrence>> found = new LinkedHashMap<>();
        TokenFinder finder = new TokenFinder(values.keySet());
        finder.setSortByPosition(true);
        // writeText() walks every page through processPage() in page order.
        finder.writeText(doc, new java.io.StringWriter());
        for (Map.Entry<String, List<Occurrence>> e : finder.found.entrySet()) {
            found.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        }

        List<String> replaced = new ArrayList<>();
        List<String> unreplaced = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            List<Occurrence> occurrences = found.getOrDefault(e.getKey(), List.of());
            if (occurrences.isEmpty()) {
                unreplaced.add(e.getKey());
                continue;
            }
            replaced.add(e.getKey());
            stampOccurrences(doc, e.getKey(), e.getValue(), occurrences);
        }
        return new StampOutcome(null, replaced, unreplaced);
    }

    private static void stampOccurrences(PDDocument doc, String token, String value,
                                         List<Occurrence> occurrences) throws IOException {
        Map<Integer, List<Occurrence>> byPage = new LinkedHashMap<>();
        occurrences.forEach(o -> byPage.computeIfAbsent(o.pageIndex, k -> new ArrayList<>()).add(o));

        Map<String, PDFont> fontCache = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Occurrence>> pageEntry : byPage.entrySet()) {
            PDPage page = doc.getPage(pageEntry.getKey());
            // PDFTextStripper reports Y downwards from the page top; PDF drawing
            // uses the bottom-left origin. Flip the baseline into PDF space.
            float pageTopY = page.getMediaBox().getUpperRightY();
            try (PDPageContentStream cs =
                         new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND,
                                 true, true)) {
                for (Occurrence o : pageEntry.getValue()) {
                    float drawY = pageTopY - o.baseY;

                    // White-out the token (descenders like {{ need padding below)
                    cs.setNonStrokingColor(Color.WHITE);
                    cs.addRect(o.x - 1f, drawY - 0.3f * o.fontSize,
                            o.width + 2f, 1.6f * o.fontSize);
                    cs.fill();

                    // Stamp the value at the token's original position and size,
                    // using the template's own font family where possible.
                    PDFont font = resolveStampFont(doc, o.fontName, fontCache);
                    cs.beginText();
                    cs.setFont(font, o.fontSize);
                    cs.setNonStrokingColor(Color.BLACK);
                    cs.newLineAtOffset(o.x, drawY);
                    cs.showText(value);
                    cs.endText();
                }
            }
        }
    }

    private static final String WINDOWS_FONTS_DIR = "C:/Windows/Fonts/";

    /**
     * Best-effort match of the template token's font. Falls back to Helvetica
     * (bold variant if the token font name says so) when no matching system
     * font file can be loaded.
     */
    private static PDFont resolveStampFont(PDDocument doc, String pdfFontName,
                                           Map<String, PDFont> cache) {
        if (pdfFontName != null && cache.containsKey(pdfFontName)) {
            return cache.get(pdfFontName);
        }
        PDFont resolved = tryResolveSystemFont(doc, pdfFontName);
        if (resolved == null) {
            boolean bold = pdfFontName != null && pdfFontName.toLowerCase().contains("bold");
            resolved = bold
                    ? new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
                    : STAMP_FONT;
        }
        if (pdfFontName != null) {
            cache.put(pdfFontName, resolved);
        }
        return resolved;
    }

    private static PDFont tryResolveSystemFont(PDDocument doc, String pdfFontName) {
        if (pdfFontName == null) {
            return null;
        }
        try {
            // Strip subset prefixes like "ABCDEF+Calibri-Bold" and style suffixes.
            String name = pdfFontName.contains("+")
                    ? pdfFontName.substring(pdfFontName.indexOf('+') + 1)
                    : pdfFontName;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            boolean bold = lower.contains("bold");
            boolean italic = lower.contains("italic") || lower.contains("oblique");

            // Common Windows font family names differ from their file names.
            String family = lower
                    .replaceAll("[-,_]?(bolditalic|bold|italic|oblique|regular)$", "")
                    .replace("psmt", "").replace("ps", "").replace(" ", "");
            Map<String, String> fileBases = Map.ofEntries(
                    Map.entry("timesnewroman", "times"),
                    Map.entry("arial", "arial"),
                    Map.entry("calibri", "calibri"),
                    Map.entry("calibrilight", "calilite"),
                    Map.entry("tahoma", "tahoma"),
                    Map.entry("verdana", "verdana"),
                    Map.entry("georgia", "georgia"),
                    Map.entry("trebuchet", "trebuc"),
                    Map.entry("couriernew", "cour"),
                    Map.entry("consolas", "consola"),
                    Map.entry("candara", "candara"),
                    Map.entry("constantia", "constan"),
                    Map.entry("corbel", "corbel"),
                    Map.entry("franklingothic", "framd"),
                    Map.entry("garamond", "gara"),
                    Map.entry("palatinolinotype", "pala"));
            String base = fileBases.getOrDefault(family, family);

            // Style suffix conventions vary: arialbd/timesbd/calibrib, ariali/calibrii...
            List<String> candidates = new ArrayList<>();
            if (bold && italic) {
                candidates.addAll(List.of(base + "bi", base + "z", base + "bolditalic"));
            } else if (bold) {
                candidates.addAll(List.of(base + "bd", base + "b", base + "bold"));
            } else if (italic) {
                candidates.addAll(List.of(base + "i", base + "italic"));
            }
            candidates.add(base); // regular

            for (String candidate : candidates) {
                for (String ext : new String[]{".ttf", ".otf"}) {
                    File file = new File(WINDOWS_FONTS_DIR, candidate + ext);
                    if (file.isFile()) {
                        try {
                            return PDType0Font.load(doc, file);
                        } catch (IOException ignored) {
                            // try the next candidate
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // any surprise falls back to Helvetica
        }
        return null;
    }

    /** Searches a document for token occurrences and records their geometry. */
    private static final class TokenFinder extends PDFTextStripper {

        private final List<String> tokens;
        private final Map<String, List<Occurrence>> found = new LinkedHashMap<>();
        private int pageCounter = -1;

        TokenFinder(Iterable<String> tokens) throws IOException {
            super();
            this.tokens = new ArrayList<>();
            tokens.forEach(this.tokens::add);
        }

        @Override
        public void processPage(PDPage page) throws IOException {
            pageCounter++; // writePages() visits pages sequentially, so this is the page index
            super.processPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions)
                throws IOException {
            if (text != null && textPositions != null && !textPositions.isEmpty()) {
                for (String token : tokens) {
                    int from = 0;
                    while (true) {
                        int start = text.indexOf(token, from);
                        if (start < 0) {
                            break;
                        }
                        int end = start + token.length() - 1; // inclusive
                        if (end < textPositions.size()) {
                            TextPosition first = textPositions.get(start);
                            TextPosition last = textPositions.get(end);
                            float x = first.getXDirAdj();
                            float baseY = first.getYDirAdj();
                            float width = (last.getXDirAdj() + last.getWidthDirAdj()) - x;
                            float fontSize = first.getFontSizeInPt();
                            float height = fontSize * 1.3f;
                            String fontName = first.getFont() != null
                                    ? first.getFont().getName() : null;
                            found.computeIfAbsent(token, k -> new ArrayList<>())
                                    .add(new Occurrence(pageCounter, x, baseY, width, height,
                                            fontSize, fontName));
                        }
                        from = start + token.length();
                    }
                }
            }
            super.writeString(text, textPositions);
        }
    }

    /** Geometry of one token occurrence; {@code pageIndex} is set by the caller. */
    private static final class Occurrence {
        int pageIndex;
        final float x;
        final float baseY;
        final float width;
        final float height;
        final float fontSize;
        final String fontName;

        Occurrence(int pageIndex, float x, float baseY, float width, float height,
                   float fontSize, String fontName) {
            this.pageIndex = pageIndex;
            this.x = x;
            this.baseY = baseY;
            this.width = width;
            this.height = height;
            this.fontSize = fontSize;
            this.fontName = fontName;
        }
    }
}
