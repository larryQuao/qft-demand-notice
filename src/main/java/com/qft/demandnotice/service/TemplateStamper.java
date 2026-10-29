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

import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * True when {@code pdf} contains at least one token placeholder anywhere in
     * its text layer. Used to warn (<em>before</em> converting with Publisher)
     * that a template has no placeholders to fill in and would be converted
     * exactly as it is.
     */
    public static boolean containsTokens(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return !findTokens(doc).isEmpty();
        }
    }

    /** Tokens present in {@code pdf}, in {@link #TOKENS} declaration order. */
    public static List<String> findTokens(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return findTokens(doc);
        }
    }

    private static List<String> findTokens(PDDocument doc) throws IOException {
        // Only the token coordinates are of interest here; the values are not
        // needed to answer "is this token present?".
        TokenFinder finder = new TokenFinder(TOKENS.keySet(), Map.of());
        finder.setSortByPosition(true);
        finder.writeText(doc, new java.io.StringWriter());
        List<String> present = new ArrayList<>();
        for (String token : TOKENS.keySet()) {
            if (!finder.found.getOrDefault(token, List.of()).isEmpty()) {
                present.add(token);
            }
        }
        return present;
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

    /**
     * An already-converted template, kept in memory so it can be stamped over
     * and over (each preview refresh) without paying for another slow
     * Microsoft Publisher conversion.
     *
     * <p>Create with {@link #prepare(Path)}. The converted bytes come from the
     * original .pub when it carries tokens, or from an auto-tokenized copy
     * when the .pub is a filled-in letter.</p>
     */
    public static final class PreparedTemplate {

        private final byte[] convertedPdf;
        private final List<String> presentTokens;
        /** Fields auto-filled by the recognizer; safe to show to the user. */
        private final List<String> autoInserted;
        /** Fields the recognizer could not locate in the .pub. */
        private final List<String> notRecognized;
        private final boolean autoAttempted;

        private PreparedTemplate(byte[] convertedPdf, List<String> presentTokens,
                                 List<String> autoInserted, List<String> notRecognized,
                                 boolean autoAttempted) {
            this.convertedPdf = convertedPdf;
            this.presentTokens = List.copyOf(presentTokens);
            this.autoInserted = List.copyOf(autoInserted);
            this.notRecognized = List.copyOf(notRecognized);
            this.autoAttempted = autoAttempted;
        }

        /** The converted template, before any values are stamped into it. */
        public byte[] convertedPdf() {
            return convertedPdf.clone();
        }

        /** Placeholder tokens that exist in the template, in declaration order. */
        public List<String> presentTokens() {
            return presentTokens;
        }

        /** True when the template contains at least one placeholder token. */
        public boolean hasTokens() {
            return !presentTokens.isEmpty();
        }

        /** Tokens that are unsupported because the template does not contain them. */
        public List<String> missingTokens() {
            return TOKENS.keySet().stream().filter(t -> !presentTokens.contains(t)).toList();
        }

        /** Fields whose placeholder the recognizer inserted into a copy of the .pub. */
        public List<String> autoInserted() {
            return autoInserted;
        }

        /** Fields the recognizer could not find in the .pub text. */
        public List<String> notRecognized() {
            return notRecognized;
        }

        /** Whether an auto-tokenize attempt was made (always true now). */
        public boolean wasAutoTokenized() {
            return autoAttempted;
        }

        /** Stamps the form values into a fresh copy of the converted template. */
        public StampOutcome stamp(DemandNoticeData data) throws IOException {
            return TemplateStamper.stamp(convertedPdf, data);
        }
    }

    /**
     * Converts a .pub template to PDF and makes sure the template carries
     * placeholders:
     *
     * <ol>
     *   <li>Convert the .pub as-is. When it already contains
     *       {@code {{...}}} tokens, that converted PDF is used directly
     *       (single Publisher pass).</li>
     *   <li>Otherwise the .pub is treated as a filled-in letter: a copy of it
     *       gets placeholders auto-inserted where the recognizer finds a
     *       date&#x2F;amount pattern ({@link PubToPdfTokenizer}), and the copy is
     *       converted. The original .pub is never modified.</li>
     * </ol>
     *
     * <p>Either way the returned {@link PreparedTemplate} holds converted bytes
     * that can be stamped repeatedly (fast), so this slow path runs once per
     * template file (and is re-run automatically when the .pub changes).</p>
     */
    public static PreparedTemplate prepare(Path pubFile) throws IOException {
        byte[] pubBytes = Files.readAllBytes(pubFile);

        // 1. Cached tokenized conversion: the fast path for filled-in .pub
        //    letters (no Microsoft Publisher run at all when it hits).
        Optional<TemplateCache.Entry> cachedTokenized =
                TemplateCache.load(pubBytes, "tokenized");
        if (cachedTokenized.isPresent()) {
            List<String> present = findTokens(cachedTokenized.get().pdf());
            if (!present.isEmpty()) {
                return new PreparedTemplate(cachedTokenized.get().pdf(), present,
                        cachedTokenized.get().replaced(), cachedTokenized.get().missed(), true);
            }
        }

        // 2. Cached plain conversion: the fast path for .pub files that carry
        //    {{...}} tokens of their own.
        Optional<TemplateCache.Entry> cachedPlain = TemplateCache.load(pubBytes, "plain");
        if (cachedPlain.isPresent()) {
            List<String> present = findTokens(cachedPlain.get().pdf());
            if (!present.isEmpty()) {
                return new PreparedTemplate(cachedPlain.get().pdf(), present,
                        List.of(), List.of(), false);
            }
        }

        // From here on: the full pipeline.
        Path convertedPdf = Files.createTempFile("qft_prepared_template_", ".pdf");
        try {
            PubToPdfConverter.convert(pubFile, convertedPdf);
            byte[] pdf = Files.readAllBytes(convertedPdf);
            List<String> present = findTokens(pdf);
            if (!present.isEmpty()) {
                TemplateCache.store(pubBytes, "plain", pdf, List.of(), List.of());
                return new PreparedTemplate(pdf, present, List.of(), List.of(), false);
            }

            // The .pub is a filled-in letter with no tokens. Copy it and insert
            // placeholders into the copy, then convert the copy.
            Path tokenizedCopy = Files.createTempFile("qft_tokenized_", ".pub");
            try {
                Files.copy(pubFile, tokenizedCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                PubToPdfTokenizer.TokenizeResult report = PubToPdfTokenizer.tokenize(tokenizedCopy);
                if (!report.alreadyTokenized() && !report.replaced().isEmpty()) {
                    Path convertedTokenized = Files.createTempFile("qft_prepared_tokenized_", ".pdf");
                    try {
                        PubToPdfConverter.convert(tokenizedCopy, convertedTokenized);
                        byte[] tokenizedPdf = Files.readAllBytes(convertedTokenized);
                        List<String> tokens = findTokens(tokenizedPdf);
                        if (!tokens.isEmpty()) {
                            TemplateCache.store(pubBytes, "tokenized", tokenizedPdf,
                                    report.replaced(), report.missed());
                        }
                        return new PreparedTemplate(tokenizedPdf, tokens,
                                report.replaced(), report.missed(), true);
                    } finally {
                        Files.deleteIfExists(convertedTokenized);
                    }
                }
                // Nothing could be tokenized: fall back to the plain conversion,
                // so the preview still shows the letter as it is.
                return new PreparedTemplate(pdf, List.of(), List.of(), report.missed(), true);
            } finally {
                Files.deleteIfExists(tokenizedCopy);
            }
        } finally {
            Files.deleteIfExists(convertedPdf);
        }
    }

    /** Stamps a loaded document in place (caller saves). */
    static StampOutcome stamp(PDDocument doc, DemandNoticeData data) throws IOException {

        Map<String, String> values = new LinkedHashMap<>();
        TOKENS.forEach((token, resolver) -> values.put(token, resolver.apply(data)));

        // 1. Locate the tokens - this has to happen before the token text is
        //    removed, because it yields the position the value is drawn at.
        TokenFinder finder = new TokenFinder(values.keySet());
        finder.setSortByPosition(true);
        // writeText() walks every page through processPage() in page order.
        finder.writeText(doc, new java.io.StringWriter());

        // 2. How much width does each drawn value need? Values are never
        //    shrunk. Each placeholder reserves a slot: the token's own advance
        //    plus the blank padding behind it (minus one separating space when
        //    the text continues with a word). The value fills that slot with
        //    letter spacing at the body's own size; only what does not fit is
        //    reported to the editor, which then shifts the rest of the line.
        Map<String, PDFont> fontCache = new LinkedHashMap<>();
        Map<String, Float> addedWidthsPt = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            List<Occurrence> occurrences =
                    finder.found.getOrDefault(e.getKey(), List.of());
            if (occurrences.isEmpty()) {
                continue;
            }
            Occurrence first = occurrences.get(0);
            PDFont font = resolveStampFont(doc, first.fontName, fontCache);
            try {
                float valueWidthPt = font.getStringWidth(e.getValue()) / 1000f
                        * first.fontSize;
                float keepSpace = keepSpaceAfter(first, font);
                float slotEnd = first.width + first.trailingBlank;
                addedWidthsPt.put(e.getKey(), Math.max(0f,
                        valueWidthPt + keepSpace - slotEnd));
            } catch (IOException | RuntimeException ex) {
                addedWidthsPt.put(e.getKey(), 0f);
            }
        }
        if (Boolean.getBoolean("qft.debug.shift")) {
            addedWidthsPt.forEach((token, excess) -> {
                Occurrence first = finder.found.get(token).get(0);
                System.err.printf("DEBUG excess %s: value+keep-slot = %.2f "
                                + "(x=%.2f w=%.2f slot=%.2f trail=%.2f)%n",
                        token, excess, first.x, first.width, first.width
                                + first.trailingBlank, first.trailingBlank);
            });
        }

        // 3. Delete the token text from the content streams so that the
        //    placeholders no longer exist in the PDF's text layer. The editor
        //    also reflows each affected line: it shifts the later runs of a
        //    line for the wider values and, where that would carry the line
        //    past its original right edge, tightens the word gaps that are
        //    not adjacent to a value.
        Set<String> deleted = new LinkedHashSet<>();
        Map<Occurrence, Float> drawShifts = new LinkedHashMap<>();
        List<PDPage> pages = new ArrayList<>();
        doc.getPages().forEach(pages::add);
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            List<ContentStreamEditor.TokenMark> marks = new ArrayList<>();
            List<Occurrence> markOccs = new ArrayList<>();
            for (Map.Entry<String, List<Occurrence>> e : finder.found.entrySet()) {
                for (Occurrence o : e.getValue()) {
                    if (o.pageIndex != pageIndex) {
                        continue;
                    }
                    marks.add(new ContentStreamEditor.TokenMark(e.getKey(),
                            o.x, o.baseY, o.nextIsPunctuation, o.spaceAdvance));
                    markOccs.add(o);
                }
            }
            ContentStreamEditor.Placement placement =
                    ContentStreamEditor.removeTokens(doc, pages.get(pageIndex),
                            new ArrayList<>(values.keySet()), addedWidthsPt, marks);
            deleted.addAll(placement.removed());
            for (int i = 0; i < markOccs.size(); i++) {
                Float shift = placement.shifts().get(i);
                if (shift != null) {
                    drawShifts.put(markOccs.get(i), shift);
                }
            }
        }

        // 4. Draw the values over the freed space. A value standing after a
        //    widened token on the same line has to land on the shifted seat.
        //    Occurrences the editor could not report a shift for fall back to
        //    the line-local estimate: the excess of all tokens that end
        //    before it on the same baseline.
        for (List<Occurrence> occurrences : finder.found.values()) {
            for (Occurrence o : occurrences) {
                if (drawShifts.containsKey(o)) {
                    continue;
                }
                float shift = 0f;
                for (Map.Entry<String, List<Occurrence>> other : finder.found.entrySet()) {
                    float otherExcess = addedWidthsPt.getOrDefault(other.getKey(), 0f);
                    if (otherExcess <= 0f) {
                        continue;
                    }
                    for (Occurrence q : other.getValue()) {
                        if (q != o && q.pageIndex == o.pageIndex
                                && Math.abs(q.baseY - o.baseY) < 2f
                                && q.x + q.width <= o.x + 0.5f) {
                            shift += otherExcess;
                        }
                    }
                }
                if (shift > 0f) {
                    drawShifts.put(o, shift);
                }
            }
        }
        if (Boolean.getBoolean("qft.debug.shift")) {
            drawShifts.forEach((o, shift) ->
                    System.err.printf("DEBUG draw shift occ x=%.1f y=%.1f -> +%.2f%n",
                            o.x, o.baseY, shift));
        }

        List<String> replaced = new ArrayList<>();
        List<String> unreplaced = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            List<Occurrence> occurrences = finder.found.getOrDefault(e.getKey(), List.of());
            if (occurrences.isEmpty()) {
                unreplaced.add(e.getKey());
                continue;
            }
            boolean wasDeleted = deleted.contains(e.getKey());
            if (wasDeleted) {
                replaced.add(e.getKey());
            } else {
                // The placeholder could not be deleted - mask it so it is at
                // least not visible, and report it as not replaced.
                unreplaced.add(e.getKey());
            }
            stampOccurrences(doc, e.getKey(), e.getValue(), occurrences, !wasDeleted,
                    fontCache, drawShifts);
        }
        return new StampOutcome(null, replaced, unreplaced);
    }

    private static void stampOccurrences(PDDocument doc, String token, String value,
                                         List<Occurrence> occurrences, boolean maskToken,
                                         Map<String, PDFont> fontCache,
                                         Map<Occurrence, Float> drawShifts)
            throws IOException {
        Map<Integer, List<Occurrence>> byPage = new LinkedHashMap<>();
        occurrences.forEach(o -> byPage.computeIfAbsent(o.pageIndex, k -> new ArrayList<>()).add(o));

        for (Map.Entry<Integer, List<Occurrence>> pageEntry : byPage.entrySet()) {
            PDPage page = doc.getPage(pageEntry.getKey());
            // PDFTextStripper reports Y downwards from the page top; PDF drawing
            // uses the bottom-left origin. Flip the baseline into PDF space.
            float pageTopY = page.getMediaBox().getUpperRightY();
            try (PDPageContentStream cs =
                         new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND,
                                 true, true)) {
                for (Occurrence o : pageEntry.getValue()) {
                    // A value standing after a widened token on the same line
                    // lands on the seat the editor shifted the runs to.
                    float drawX = o.x + drawShifts.getOrDefault(o, 0f);
                    float drawY = pageTopY - o.baseY;

                    if (maskToken) {
                        // The placeholder text is still in the content stream -
                        // cover it so it is not visible (descenders like {{ need
                        // padding below).
                        cs.setNonStrokingColor(Color.WHITE);
                        cs.addRect(drawX - 1f, drawY - 0.3f * o.fontSize,
                                o.width + 2f, 1.6f * o.fontSize);
                        cs.fill();
                    }

                    // Stamp the value at the token's original position, size and
                    // font family: the value must look exactly like the body
                    // text - it is never shrunk. When it is narrower than the
                    // placeholder's reserved span, the missing width is spread
                    // as letter spacing (the way justified text behaves), so no
                    // gap is left. When it is wider, the content stream editor
                    // has already shifted the rest of the line to make room.
                    PDFont font = resolveStampFont(doc, o.fontName, fontCache);
                    float valueWidth = o.width; // safe default if metrics fail
                    try {
                        valueWidth = font.getStringWidth(value) / 1000f * o.fontSize;
                    } catch (IOException | RuntimeException ignored) {
                        // fall through with the token's own width
                    }
                    float keepSpace = keepSpaceAfter(o, font);
                    float fillTarget = o.width + o.trailingBlank - keepSpace;

                    // Slack between the value and the reserved slot is spread
                    // without ever changing the font size: first into the
                    // value's own word gaps (up to twice the template's blank
                    // advance, the way justified text behaves), then as letter
                    // spacing inside the words. Nothing moves around the token.
                    List<String> words = new ArrayList<>();
                    for (String word : value.split(" ")) {
                        if (!word.isEmpty()) {
                            words.add(word);
                        }
                    }
                    int wordGaps = words.size() - 1;
                    int wordChars = words.stream().mapToInt(String::length).sum();
                    int inWordGaps = Math.max(1, wordChars - words.size());
                    float spaceWidth = fontSpaceWidth(font, o.fontSize);
                    float templateGap = o.spaceAdvance > 0f ? o.spaceAdvance : spaceWidth;
                    boolean measurable = true;
                    float[] wordInks = new float[words.size()];
                    try {
                        for (int k = 0; k < words.size(); k++) {
                            wordInks[k] = font.getStringWidth(words.get(k)) / 1000f
                                    * o.fontSize;
                        }
                    } catch (IOException | RuntimeException ex) {
                        measurable = false;
                    }

                    float letterSpacing = 0f;
                    float gapAdvance = spaceWidth;
                    float deficit = fillTarget - valueWidth;
                    if (!measurable) {
                        words.clear();
                        words.add(value);
                        wordGaps = 0;
                    } else if (deficit > 0f && wordGaps == 0) {
                        // single word - only letter spacing can absorb the slack
                        letterSpacing = Math.min(deficit
                                        / Math.max(1, value.length() - 1),
                                o.fontSize * 0.35f);
                    } else if (deficit > 0f) {
                        gapAdvance = Math.min(2f * templateGap,
                                spaceWidth + deficit / wordGaps);
                        float leftover = deficit - (gapAdvance - spaceWidth) * wordGaps;
                        letterSpacing = Math.min(leftover / inWordGaps,
                                o.fontSize * 0.35f);
                    }

                    cs.beginText();
                    cs.setFont(font, o.fontSize);
                    cs.setNonStrokingColor(Color.BLACK);
                    if (letterSpacing > 0f) {
                        cs.setCharacterSpacing(letterSpacing);
                    }
                    if (letterSpacing == 0f && gapAdvance == spaceWidth) {
                        cs.newLineAtOffset(drawX, drawY);
                        cs.showText(value);
                    } else {
                        // Each word gets its own text matrix: Td moves relative
                        // to the last Td origin, not to the glyph cursor.
                        float cursorX = drawX;
                        for (int k = 0; k < words.size(); k++) {
                            cs.setTextMatrix(Matrix.getTranslateInstance(cursorX, drawY));
                            cs.showText(words.get(k));
                            if (k < wordGaps) {
                                // Tc stretches the word's ink by (len-1)·G too;
                                // the next word starts one gapAdvance after that
                                cursorX = cursorX + wordInks[k]
                                        + (words.get(k).length() - 1) * letterSpacing
                                        + gapAdvance;
                            }
                        }
                    }
                    if (letterSpacing > 0f) {
                        // Tc is a sticky text-state operator - reset it
                        cs.setCharacterSpacing(0f);
                    }
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

    /** Width of one space glyph at the given size, with a safe fallback. */
    private static float fontSpaceWidth(PDFont font, float fontSize) {
        if (fontSize <= 0f) {
            return 3f;
        }
        try {
            return Math.max(1f, font.getStringWidth(" ") / 1000f * fontSize);
        } catch (IOException | IllegalArgumentException ex) {
            return 0.3f * fontSize; // reasonable default for most fonts
        }
    }

    /**
     * Space that must remain between the stamped value and the next visible
     * character: none before punctuation (a comma or period glues to the
     * value). Before anything else exactly one of the placeholder's own blank
     * glyphs is kept; when the placeholder had no blanks behind it, one space
     * width of the resolved font is used instead.
     */
    private static float keepSpaceAfter(Occurrence occurrence, PDFont font) {
        if (occurrence.nextIsPunctuation) {
            return 0f;
        }
        if (occurrence.spaceAdvance > 0f) {
            return occurrence.spaceAdvance;
        }
        try {
            return Math.max(0f, font.getStringWidth(" ") / 1000f * occurrence.fontSize);
        } catch (IOException | IllegalArgumentException ex) {
            return 0.3f * occurrence.fontSize; // reasonable default for most fonts
        }
    }

    /** Searches a document for token occurrences and records their geometry. */
    private static final class TokenFinder extends PDFTextStripper {

        private final List<String> tokens;
        private final Map<String, List<Occurrence>> found = new LinkedHashMap<>();
        private int pageCounter = -1;

        TokenFinder(Iterable<String> tokens) throws IOException {
            this(tokens, Map.of());
        }

        /**
         * @param tokens tokens to search for
         * @param values token values; unused, kept so callers can pass both in a
         *               way that makes the intent explicit
         */
        TokenFinder(Iterable<String> tokens, Map<String, String> values) throws IOException {
            super();
            this.tokens = new ArrayList<>();
            tokens.forEach(this.tokens::add);
            for (String token : values.keySet()) {
                if (!this.tokens.contains(token)) {
                    this.tokens.add(token);
                }
            }
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
                            // True advance of the blank glyph run right after the
                            // token (the padding the auto-tokenizer inserted): the
                            // next visible glyph's x minus the token's end. That
                            // includes word spacing (Tw), which plain glyph widths
                            // leave out, and it tells how far the stamped value
                            // may spread. The average per-blank advance is kept
                            // for the one separator space that must be kept.
                            float tokenEndX = x + width;
                            float blankRunStart = -1f;
                            int blankCount = 0;
                            float trailingBlank = 0f;   // full advance of the run
                            float spaceAdvance = 0f;    // advance of one blank
                            boolean nextIsPunctuation = true; // line end counts as punctuation
                            for (int j = end + 1; j < textPositions.size(); j++) {
                                TextPosition candidate = textPositions.get(j);
                                String u = candidate.getUnicode();
                                if (u == null || u.isBlank()) {
                                    if (blankRunStart < 0) {
                                        blankRunStart = candidate.getXDirAdj();
                                    }
                                    blankCount++;
                                    continue;
                                }
                                float nextInkX = candidate.getXDirAdj();
                                if (blankRunStart >= 0) {
                                    trailingBlank = nextInkX - tokenEndX;
                                    spaceAdvance = (nextInkX - blankRunStart) / blankCount;
                                }
                                char c = u.charAt(0);
                                nextIsPunctuation = c == '.' || c == ',' || c == ';'
                                        || c == ':' || c == '!' || c == '?'
                                        || c == ')' || c == ']';
                                break;
                            }
                            found.computeIfAbsent(token, k -> new ArrayList<>())
                                    .add(new Occurrence(pageCounter, x, baseY, width,
                                            height, fontSize, fontName, trailingBlank,
                                            spaceAdvance, nextIsPunctuation));
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
        /** True rendered advance of the blank run directly after the token. */
        final float trailingBlank;
        /** Advance of one blank glyph inside that run (0 when there is none). */
        final float spaceAdvance;
        /** True when the next visible character is punctuation or the line ends. */
        final boolean nextIsPunctuation;

            Occurrence(int pageIndex, float x, float baseY, float width, float height,
                   float fontSize, String fontName, float trailingBlank,
                   float spaceAdvance, boolean nextIsPunctuation) {
            this.pageIndex = pageIndex;
            this.x = x;
            this.baseY = baseY;
            this.width = width;
            this.height = height;
            this.fontSize = fontSize;
            this.fontName = fontName;
            this.trailingBlank = trailingBlank;
            this.spaceAdvance = spaceAdvance;
            this.nextIsPunctuation = nextIsPunctuation;
        }

        @Override
        public String toString() {
            return "Occurrence[page=" + pageIndex + " x=" + String.format("%.2f", x)
                    + " width=" + String.format("%.2f", width)
                    + " size=" + String.format("%.2f", fontSize)
                    + " font=" + fontName
                    + " trail=" + String.format("%.2f", trailingBlank)
                    + " space=" + String.format("%.2f", spaceAdvance)
                    + " punct=" + nextIsPunctuation + "]";
        }
    }
}
