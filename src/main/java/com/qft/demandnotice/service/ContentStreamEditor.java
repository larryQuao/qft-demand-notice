package com.qft.demandnotice.service;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Removes literal placeholder tokens (for example {@code {{AMOUNT}}}) from a
 * page's content stream so that they no longer exist in the PDF's text layer.
 *
 * <p>Only the characters that make up a token are deleted; every other byte of
 * the content stream is preserved exactly. The horizontal advance the removed
 * characters occupied is restored with a {@code TJ} adjustment, so the rest of
 * the line keeps its position and the caller can draw the replacement value
 * into the freed space.</p>
 *
 * <p>Tokens are located inside show-text operators ({@code Tj}, {@code TJ},
 * {@code '} and {@code "}), decoded with the font that is active at that point
 * in the stream. A token that is split across several strings of one
 * {@code TJ} array is handled as well.</p>
 */
final class ContentStreamEditor {

    private ContentStreamEditor() {
    }

    /**
     * One occurrence of a token in the page's text, as located by the
     * stamper's text scan: the token text, its first character's x position
     * (page points), its baseline measured from the page top, and whether
     * punctuation follows the token.
     */
    record TokenMark(String token, float x, float baseY, boolean punctAfter,
                     float spaceAdvance) {
    }

    /**
     * Result of rewriting one page: the removed tokens plus, parallel to the
     * {@link TokenMark} list passed in, the x offset (points) each value must
     * use so that it stays on the seat its placeholder occupied after the
     * line was reflowed. A shift is {@code null} when no matching edit run
     * was found; the caller then falls back to its own estimate.
     */
    record Placement(Set<String> removed, List<Float> shifts) {
    }

    /**
     * Removes every occurrence of the given tokens from the page's text.
     *
     * @param doc          the document the page belongs to (used to store the result)
     * @param page         the page to rewrite
     * @param tokens       the literal token strings, e.g. {@code "{{AMOUNT}}"}
     * @param addedWidthPt width in points that each drawn replacement value
     *                     needs, per token. When the value is wider than the
     *                     token it replaces, the rest of the line is shifted
     *                     right by the excess (values are never shrunk); when
     *                     it is narrower, the line stays where it was.
     * @param marks        the caller's token occurrences on this page, used to
     *                     report back per-occurrence draw offsets
     * @return the tokens that were found and removed, plus per-mark shifts
     */
    static Placement removeTokens(PDDocument doc, PDPage page, List<String> tokens,
                                  Map<String, Float> addedWidthPt,
                                  List<TokenMark> marks) throws IOException {
        byte[] content = page.getContents().readAllBytes();
        if (content.length == 0 || tokens.isEmpty()) {
            return new Placement(Set.of(),
                    Collections.nCopies(marks.size(), (Float) null));
        }

        List<Patch> patches = new ArrayList<>();
        Set<String> removed = new LinkedHashSet<>();

        PDFont font = null;
        float fontSize = 0f;
        float charSpacing = 0f;
        float wordSpacing = 0f;
        List<Value> operands = new ArrayList<>();

        // --- text placement tracking -----------------------------------
        // Publisher's PDFs place every run of a justified line in its own
        // "BT /F1 ... Tf 1 0 0 1 x y Tm ... TJ ET" block. A TJ adjustment
        // inserted inside one block can never move the runs that follow it,
        // so when a drawn value is wider than the placeholder it replaces,
        // the excess is additionally patched into the Tm of every later run
        // on the same baseline. The state below mirrors just enough of the
        // text positioning operators (BT, Tm, Td/TD, T*, TL, ET) to know
        // where each run starts.
        float textX = 0f;   // text matrix translation before the current run
        float textY = 0f;
        float leading = 0f;
        int posOpStart = -1;         // stream offset of the run's own Tm
        int posOperandStart = -1;    // byte range of the Tm's e operand
        int posOperandEnd = -1;
        float posOperandNumber = 0f; // the e operand's original value
        boolean posPatchable = false; // a directly preceding, usable Tm exists

        // Every show-text run of the page, in stream order: the reflow pass
        // after the scan groups them per baseline and decides final shifts.
        List<RunSlot> runs = new ArrayList<>();
        // Token hits announced while scanning, in stream order.
        List<HitLine> hits = new ArrayList<>();

        for (Object item : scan(content)) {
            if (item instanceof Value value) {
                operands.add(value);
                continue;
            }
            Operator operator = (Operator) item;
            try {
                switch (operator.name) {
                    case "BT" -> {
                        textX = 0f;
                        textY = 0f;
                        leading = 0f;
                        posOpStart = -1;
                        posPatchable = false;
                    }
                    case "ET" -> {
                        posOpStart = -1;
                        posPatchable = false;
                    }
                    case "Tm" -> {
                        if (operands.size() >= 6) {
                            float a = (float) operands.get(0).number;
                            float b = (float) operands.get(1).number;
                            float c = (float) operands.get(2).number;
                            float d = (float) operands.get(3).number;
                            textX = (float) operands.get(4).number;
                            textY = (float) operands.get(5).number;
                            posOpStart = operator.start;
                            posOperandStart = operands.get(4).start;
                            posOperandEnd = operands.get(4).end;
                            posOperandNumber = textX;
                            // Only untitled, unscaled matrices are patched as
                            // absolute x shifts; anything else is left alone.
                            posPatchable = a > 0.99f && a < 1.01f
                                    && Math.abs(b) < 0.01f && Math.abs(c) < 0.01f
                                    && d > 0.99f && d < 1.01f
                                    && operands.get(4).kind == Kind.NUMBER;
                        }
                    }
                    case "Td", "TD" -> {
                        if (operands.size() >= 2) {
                            textX += (float) operands.get(0).number;
                            textY += (float) operands.get(1).number;
                            if (operator.name.equals("TD")) {
                                leading = -(float) operands.get(1).number;
                            }
                            // Relative moves share their lineage with the
                            // previous line - shifting them is not safe, so
                            // they invalidate the patchable position.
                            posOpStart = -1;
                            posPatchable = false;
                        }
                    }
                    case "TL" -> {
                        if (!operands.isEmpty()) {
                            leading = (float) operands.get(operands.size() - 1).number;
                        }
                    }
                    case "T*" -> {
                        textY -= leading;
                        posOpStart = -1;
                        posPatchable = false;
                    }
                    case "q", "Q", "cm" -> {
                        posOpStart = -1;
                        posPatchable = false;
                    }
                    case "Tf" -> {
                        if (operands.size() >= 2) {
                            fontSize = (float) operands.get(operands.size() - 1).number;
                            font = fontFor(page, operands.get(operands.size() - 2).name);
                        }
                    }
                    case "Tc" -> {
                        if (!operands.isEmpty()) {
                            charSpacing = (float) operands.get(operands.size() - 1).number;
                        }
                    }
                    case "Tw" -> {
                        if (!operands.isEmpty()) {
                            wordSpacing = (float) operands.get(operands.size() - 1).number;
                        }
                    }
                    case "Tj", "'", "\"" -> {
                        if (!operands.isEmpty()) {
                            Value string = operands.get(operands.size() - 1);
                            if (string.kind == Kind.STRING) {
                                RewriteResult result = rewrite(textState(font, fontSize,
                                        charSpacing, wordSpacing),
                                        List.of(string), operator, operands, patches, tokens,
                                        addedWidthPt);
                                removed.addAll(result.removed());
                                for (HitShift hit : result.entries()) {
                                    hits.add(new HitLine(hit.token(), textY,
                                            textX + hit.relEndX(),
                                            textX + hit.relEndX() + hit.inkEndX(),
                                            hit.excess(),
                                            operator.start));
                                }
                                runs.add(new RunSlot(operator.start, textY, textX,
                                        result.advance(), result.inkAdvance(),
                                        result.leadAdvance(), fontSize,
                                        posOperandStart,
                                        posOperandEnd, posOperandNumber, posPatchable));
                            }
                        }
                    }
                    case "TJ" -> {
                        if (!operands.isEmpty()) {
                            Value array = operands.get(operands.size() - 1);
                            if (array.kind == Kind.ARRAY) {
                                List<Value> strings = new ArrayList<>();
                                for (Value element : array.elements) {
                                    if (element.kind == Kind.STRING) {
                                        strings.add(element);
                                    }
                                }
                                RewriteResult result = rewrite(textState(font, fontSize,
                                        charSpacing, wordSpacing),
                                        strings, operator, operands, patches, tokens,
                                        addedWidthPt);
                                removed.addAll(result.removed());
                                for (HitShift hit : result.entries()) {
                                    hits.add(new HitLine(hit.token(), textY,
                                            textX + hit.relEndX(),
                                            textX + hit.relEndX() + hit.inkEndX(),
                                            hit.excess(),
                                            operator.start));
                                }
                                runs.add(new RunSlot(operator.start, textY, textX,
                                        result.advance(), result.inkAdvance(),
                                        result.leadAdvance(), fontSize,
                                        posOperandStart,
                                        posOperandEnd, posOperandNumber, posPatchable));
                            }
                        }
                    }
                    default -> {
                    }
                }
            } finally {
                operands.clear();
            }
        }

        // --- reflow pass: decide the final shift of every run -----------
        ReflowOut reflow = reflowLines(runs, hits, marks);
        patches.addAll(reflow.patches());

        if (!patches.isEmpty()) {
            PDStream stream = new PDStream(doc);
            try (OutputStream out = stream.createOutputStream()) {
                out.write(applyPatches(content, patches));
            }
            page.setContents(stream);
        }
        return new Placement(removed, reflow.markShifts());
    }

    // ------------------------------------------------------------------
    // Line reflow: final shifts per run with bounded gap compression
    // ------------------------------------------------------------------

    private record ReflowOut(List<Patch> patches, List<Float> markShifts) {
    }

    /** One show-text run of the page, seen while scanning. */
    private record RunSlot(int opStart, float textY, float x, float advance,
                           float inkAdvance, float leadAdvance, float fontSize,
                           int operandStart, int operandEnd, float operandNumber,
                           boolean patchable) {
    }

    /** A token hit announced by {@link #rewrite}, mutable for mark zipping. */
    private static final class HitLine {
        final String token;
        final float lineY;
        final float endX;
        final float inkEndX;
        final float excess;
        final int opStart;
        int markIndex = -1;
        boolean punctAfter;
        float keepSpace;

        HitLine(String token, float lineY, float endX, float inkEndX,
                float excess, int opStart) {
            this.token = token;
            this.lineY = lineY;
            this.endX = endX;
            this.inkEndX = inkEndX;
            this.excess = excess;
            this.opStart = opStart;
        }
    }

    /**
     * Decides, per text line, how far every run must move so that the values
     * drawn over their placeholders fit, and returns the resulting patches
     * plus the draw offset of every zipped mark.
     *
     * <p>Base rule (always applied): a run standing after a widened token
     * moves right by the excess collected before it (its own operator's
     * excess is excluded - the tail after the token is already shifted
     * inside the operator itself).</p>
     *
     * <p>Second rule: when that would carry the line past its original
     * right edge, the tail is pulled back by tightening the word gaps that
     * are <em>not</em> adjacent to a value (a value's reserved keep-space is
     * part of the excess formula and is never touched), bounded by a
     * readable floor. The line then keeps its original width as far as the
     * slack allows; anything beyond that has to overflow.</p>
     */
    private static ReflowOut reflowLines(List<RunSlot> runs, List<HitLine> hits,
                                         List<TokenMark> marks) {
        List<Float> markShifts = new ArrayList<>(Collections.nCopies(marks.size(), (Float) null));
        List<Patch> out = new ArrayList<>();
        if (runs.isEmpty() || hits.isEmpty()) {
            return new ReflowOut(out, markShifts);
        }

        // --- zip each token's hits with the caller's marks --------------
        Map<String, List<HitLine>> byToken = new LinkedHashMap<>();
        for (HitLine h : hits) {
            byToken.computeIfAbsent(h.token, k -> new ArrayList<>()).add(h);
        }
        Map<String, List<Integer>> markIdxByToken = new LinkedHashMap<>();
        for (int i = 0; i < marks.size(); i++) {
            TokenMark m = marks.get(i);
            markIdxByToken.computeIfAbsent(m.token(), k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<String, List<HitLine>> e : byToken.entrySet()) {
            List<HitLine> list = e.getValue();
            // reading order: descending text y (= ascending from page top),
            // then by x
            list.sort((a, b) -> {
                int byY = Float.compare(b.lineY, a.lineY);
                return byY != 0 ? byY : Float.compare(a.endX, b.endX);
            });
            List<Integer> mi = markIdxByToken.get(e.getKey());
            if (mi == null || mi.size() != list.size()) {
                continue;
            }
            mi.sort((a, b) -> {
                TokenMark ma = marks.get(a);
                TokenMark mb = marks.get(b);
                int byY = Float.compare(ma.baseY(), mb.baseY());
                return byY != 0 ? byY : Float.compare(ma.x(), mb.x());
            });
            for (int i = 0; i < list.size(); i++) {
                HitLine h = list.get(i);
                h.markIndex = mi.get(i);
                h.punctAfter = marks.get(h.markIndex).punctAfter();
                h.keepSpace = h.punctAfter ? 0f : marks.get(h.markIndex).spaceAdvance();
            }
        }

        // --- group runs into lines by baseline y ------------------------
        List<List<RunSlot>> lineRuns = new ArrayList<>();
        List<Float> lineY = new ArrayList<>();
        for (RunSlot r : runs) {
            int found = -1;
            for (int i = 0; i < lineY.size(); i++) {
                if (Math.abs(lineY.get(i) - r.textY) < 1.5f) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                lineRuns.add(new ArrayList<>());
                lineY.add(r.textY);
                found = lineRuns.size() - 1;
            }
            lineRuns.get(found).add(r);
        }

        Set<Integer> patchedOperands = new HashSet<>();
        for (int li = 0; li < lineRuns.size(); li++) {
            List<RunSlot> line = lineRuns.get(li);
            List<HitLine> lineHits = new ArrayList<>();
            for (HitLine h : hits) {
                if (Math.abs(h.lineY - lineY.get(li)) < 1.5f) {
                    lineHits.add(h);
                }
            }
            if (lineHits.isEmpty()) {
                continue;
            }
            line.sort(Comparator.comparingDouble(r -> r.x));
            int n = line.size();

            // per run: cumulative excess collected before it
            Map<Integer, Integer> runByOp = new HashMap<>();
            float[] shift = new float[n];
            for (int i = 0; i < n; i++) {
                RunSlot r = line.get(i);
                runByOp.putIfAbsent(r.opStart, i);
                float acc = 0f;
                for (HitLine h : lineHits) {
                    if (h.endX <= r.x + 0.5f && h.opStart != r.opStart) {
                        acc += h.excess;
                    }
                }
                shift[i] = acc;
            }

            // second layer: bounded gap compression
            boolean compressible = n >= 2;
            for (RunSlot r : line) {
                if (!r.patchable || Float.isNaN(r.advance)) {
                    compressible = false;
                }
            }
            float demand = shift[n - 1];
            float[] cap = new float[n - 1];
            if (compressible && demand > 0.01f) {
                float avail = 0f;
                for (int i = 0; i < n - 1; i++) {
                    RunSlot prev = line.get(i);
                    float gapAdv = line.get(i + 1).x - (prev.x + prev.advance);
                    float gapVis = line.get(i + 1).x - (prev.x + prev.inkAdvance);
                    float jump = shift[i + 1] - shift[i];
                    float floor;
                    // is a drawn value's ink touching this boundary?
                    boolean valueAdjacent = jump > 0.01f;
                    for (HitLine h : lineHits) {
                        if (h.inkEndX >= line.get(i).x - 0.5f
                                && h.inkEndX <= line.get(i + 1).x + 0.5f) {
                            valueAdjacent = true;
                            break;
                        }
                    }
                    if (valueAdjacent) {
                        // the reserved keep-space (natural space width) or the
                        // punctuation glue lives here; the excess model already
                        // puts the next ink exactly where it belongs - do not
                        // compress
                        cap[i] = 0f;
                    } else if (line.get(i).inkAdvance < 0.001f
                            || line.get(i + 1).inkAdvance < 0.001f) {
                        // an entirely blank run borders this gap: it is part
                        // of a value's reserved padding zone
                        cap[i] = 0f;
                    } else {
                        // plain word boundary: measured from the ink, so the
                        // run's own trailing blanks count as slack
                        floor = 0.12f * Math.max(1f, line.get(i + 1).fontSize);
                        cap[i] = Math.max(0f, gapVis - floor);
                    }
                    avail += cap[i];
                }
                if (avail > 0.01f) {
                    float k = Math.min(1f, demand / avail);
                    float cum = 0f;
                    for (int i = 0; i < n; i++) {
                        shift[i] -= cum;
                        if (i < n - 1) {
                            cum += cap[i] * k;
                        }
                    }
                }
            }
            // apply and report
            for (int i = 0; i < n; i++) {
                RunSlot r = line.get(i);
                if (r.patchable && Math.abs(shift[i]) > 0.01f
                        && patchedOperands.add(r.operandStart)) {
                    if (Boolean.getBoolean("qft.debug.shift")) {
                        System.err.printf(
                                "DEBUG patch Tm@%d: x %.2f -> %.2f (shift %.2f, y=%.1f)%n",
                                r.opStart, r.operandNumber,
                                r.operandNumber + shift[i], shift[i], r.textY);
                    }
                    out.add(new Patch(r.operandStart, r.operandEnd,
                            formatNumber(r.operandNumber + shift[i])
                                    .getBytes(StandardCharsets.ISO_8859_1)));
                }
                for (HitLine h : lineHits) {
                    if (h.markIndex >= 0 && runByOp.get(h.opStart) == i) {
                        markShifts.set(h.markIndex, r.patchable ? shift[i] : 0f);
                    }
                }
            }
        }
        return new ReflowOut(out, markShifts);
    }


    // ------------------------------------------------------------------
    // Rewriting a single show-text operator
    // ------------------------------------------------------------------

    /**
     * Locates tokens in the given strings and schedules the patches that
     * remove them. Every removed run of characters is replaced by a
     * compensating {@code TJ} adjustment, so the text after the token keeps
     * its position inside this operator. Additionally the caller learns,
     * per hit that has excess width, where the removed token ended relative
     * to this operator's text origin ({@code HitShift}) so the following
     * runs on the same line can be shifted}.
     */
    private static RewriteResult rewrite(TextState state,
                                         List<Value> strings, Operator operator,
                                         List<Value> operands, List<Patch> patches,
                                         List<String> tokens,
                                         Map<String, Float> addedWidthPt) {
        Set<String> removed = new LinkedHashSet<>();
        List<HitShift> entries = new ArrayList<>();
        if (strings.isEmpty()) {
            return new RewriteResult(removed, entries, 0f, 0f, 0f);
        }

        // --- decode the strings with the active font -------------------
        List<List<Decoded>> decoded = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Value string : strings) {
            List<Decoded> chars = decode(state.font, string.bytes);
            if (chars == null) {
                return new RewriteResult(removed, entries, Float.NaN, Float.NaN, Float.NaN); // cannot decode - leave it alone
            }
            decoded.add(chars);
            for (Decoded c : chars) {
                c.textStart = text.length();
                text.append(c.unicode);
                c.textEnd = text.length();
            }
        }
        String full = text.toString();

        // --- find the tokens ------------------------------------------
        List<Object[]> hits = new ArrayList<>(); // {start, end, token}
        for (String token : tokens) {
            int from = 0;
            while (true) {
                int index = full.indexOf(token, from);
                if (index < 0) {
                    break;
                }
                hits.add(new Object[]{index, index + token.length(), token});
                from = index + token.length();
            }
        }
        // Cumulative advance (points) before every character of each string.
        // Needed to report where a removed token ended within this operator,
        // and always, to sum up the run's total ink advance.
        List<float[]> advances = new ArrayList<>();
        float advance = 0f;
        for (List<Decoded> chars : decoded) {
            float[] arr = new float[chars.size() + 1];
            float acc = 0f;
            for (int i = 0; i < chars.size(); i++) {
                arr[i] = acc;
                Decoded d = chars.get(i);
                acc += d.width / 1000f * state.fontSize
                        + state.charSpacing
                        + (d.wordSpacingApplies ? state.wordSpacing : 0f);
            }
            arr[chars.size()] = acc;
            advances.add(arr);
            advance += acc;
        }
        // Ink advance: the total advance minus the trailing blank glyphs, so
        // the reflow pass can measure the visual gap between two runs.
        float ink = advance;
        trailing:
        for (int s = decoded.size() - 1; s >= 0; s--) {
            List<Decoded> chars = decoded.get(s);
            for (int c = chars.size() - 1; c >= 0; c--) {
                Decoded d = chars.get(c);
                if (!d.unicode.isBlank()) {
                    break trailing;
                }
                ink -= d.width / 1000f * state.fontSize
                        + state.charSpacing + state.wordSpacing;
            }
        }

        // Leading blank advance: a run's own leading blanks are invisible and
        // can host compression when the next run is pulled back over them.
        float lead = 0f;
        leading:
        for (int s = 0; s < decoded.size(); s++) {
            List<Decoded> chars = decoded.get(s);
            for (int c = 0; c < chars.size(); c++) {
                Decoded d = chars.get(c);
                if (d.unicode == null || !d.unicode.isBlank()) {
                    break leading;
                }
                lead += d.width / 1000f * state.fontSize
                        + state.charSpacing
                        + (d.wordSpacingApplies ? state.wordSpacing : 0f);
            }
        }

        if (hits.isEmpty()) {
            return new RewriteResult(removed, entries, advance, ink, lead);
        }
        hits.sort(Comparator.comparingInt(hit -> (int) hit[0]));

        // --- line-shift budget per hit ---------------------------------
        // The value is drawn over the space the token freed. The caller passes,
        // per token, how far the rest of the line must move right to leave room
        // for the value (already net of the token's own advance) - the shift is
        // applied once, at the hit's first deleted character.
        Float[] hitExtra = new Float[hits.size()];
        for (int h = 0; h < hits.size(); h++) {
            float addedPt = addedWidthPt.getOrDefault((String) hits.get(h)[2], 0f);
            hitExtra[h] = addedPt > 0f ? addedPt : 0f;
        }
        // Ink end advance of every hit's deleted region: the advance up to
        // the last deleted character's own end. Used to mark boundaries that
        // a drawn value's ink touches (value-adjacent).
        float[] hitInkEnd = new float[hits.size()];
        for (int h = 0; h < hits.size(); h++) {
            int start = (int) hits.get(h)[0];
            int end = (int) hits.get(h)[1];
            boolean haveFirst = false;
            for (List<Decoded> chars : decoded) {
                for (int c = 0; c < chars.size(); c++) {
                    Decoded d = chars.get(c);
                    if (d.textStart < end && d.textEnd > start) {
                        if (!haveFirst) {
                            haveFirst = true;
                            hitInkEnd[h] = d.width / 1000f * state.fontSize
                                    + state.charSpacing
                                    + (d.wordSpacingApplies ? state.wordSpacing : 0f);
                        } else {
                            hitInkEnd[h] += d.width / 1000f * state.fontSize
                                    + state.charSpacing
                                    + (d.wordSpacingApplies ? state.wordSpacing : 0f);
                        }
                    }
                }
            }
        }
        // The excess applies at the hit's first deleted character.
        List<List<Float>> charExtra = new ArrayList<>();
        for (List<Decoded> chars : decoded) {
            List<Float> extras = new ArrayList<>(chars.size());
            for (int i = 0; i < chars.size(); i++) {
                extras.add(0f);
            }
            charExtra.add(extras);
        }
        float[] hitEndX = new float[hits.size()];
        for (int h = 0; h < hits.size(); h++) {
            hitEndX[h] = -1f;
            float extra = hitExtra[h];
            int start = (int) hits.get(h)[0];
            for (int s = 0; s < decoded.size() && hitEndX[h] < 0; s++) {
                List<Decoded> chars = decoded.get(s);
                for (int c = 0; c < chars.size(); c++) {
                    Decoded d = chars.get(c);
                    if (d.textStart >= start && d.textEnd > start) {
                        charExtra.get(s).set(c, charExtra.get(s).get(c) + extra);
                        hitEndX[h] = advances.get(s)[c];
                        break;
                    }
                }
            }
        }

        // --- mark the characters to delete ------------------------------
        List<List<Boolean>> drop = new ArrayList<>();
        for (List<Decoded> chars : decoded) {
            List<Boolean> flags = new ArrayList<>(chars.size());
            for (int i = 0; i < chars.size(); i++) {
                flags.add(false);
            }
            drop.add(flags);
        }
        for (Object[] hit : hits) {
            for (int s = 0; s < decoded.size(); s++) {
                List<Decoded> chars = decoded.get(s);
                for (int c = 0; c < chars.size(); c++) {
                    Decoded d = chars.get(c);
                    if (d.textStart < (int) hit[1] && d.textEnd > (int) hit[0]) {
                        drop.get(s).set(c, true);
                    }
                }
            }
        }

        // --- rebuild the strings ---------------------------------------
        // The `"` operator carries its own word/char spacing operands, and they
        // apply to the string it shows.
        TextState effective = state;
        if ("\"".equals(operator.name) && operands.size() >= 3) {
            effective = new TextState(state.font, state.fontSize,
                    (float) operands.get(operands.size() - 2).number,
                    (float) operands.get(operands.size() - 3).number);
        }

        boolean changed = false;
        for (int s = 0; s < strings.size(); s++) {
            Value string = strings.get(s);
            List<Decoded> chars = decoded.get(s);
            List<Boolean> flags = drop.get(s);

            boolean any = false;
            for (Boolean flag : flags) {
                if (flag) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                continue;
            }
            // The decoded characters must cover the whole string, otherwise
            // rebuilding it would silently drop the uncovered bytes.
            if (!coversWholeString(chars, string.bytes.length)) {
                continue;
            }

            // Split the string at every removed run: the surviving bytes on
            // either side become separate strings with the compensating
            // adjustment in between, so the text after the token keeps its
            // position.
            List<String> parts = new ArrayList<>();
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            float runWidth = 0f;
            int runChars = 0;
            int runSpaces = 0;
            float runExtra = 0f; // line-shift budget (points) starting in this run
            for (int c = 0; c < chars.size(); c++) {
                Decoded d = chars.get(c);
                if (flags.get(c)) {
                    if (kept.size() > 0) {
                        parts.add(toHexString(kept.toByteArray()));
                        kept.reset();
                    }
                    if (runChars == 0) {
                        runExtra = charExtra.get(s).get(c);
                    }
                    runWidth += d.width;
                    runChars++;
                    if (d.wordSpacingApplies) {
                        runSpaces++;
                    }
                } else {
                    if (runChars > 0) {
                        parts.add(formatNumber(adjustmentFor(effective, runWidth,
                                runChars, runSpaces, runExtra)));
                        runWidth = 0f;
                        runChars = 0;
                        runSpaces = 0;
                        runExtra = 0f;
                    }
                    kept.write(string.bytes, d.byteStart, d.byteLength);
                }
            }
            if (kept.size() > 0) {
                parts.add(toHexString(kept.toByteArray()));
            }
            if (runChars > 0) {
                parts.add(formatNumber(adjustmentFor(effective, runWidth,
                        runChars, runSpaces, runExtra)));
            }
            if (parts.isEmpty()) {
                continue;
            }
            changed = true;

            // White space between the parts keeps numbers and strings apart:
            // two adjacent numbers are not valid PDF.
            String joined = " " + String.join(" ", parts) + " ";

            switch (operator.name) {
                case "TJ" -> patches.add(new Patch(string.start, string.end,
                        joined.getBytes(StandardCharsets.ISO_8859_1)));
                case "Tj" -> {
                    patches.add(new Patch(string.start, string.end,
                            ("[" + joined).getBytes(StandardCharsets.ISO_8859_1)));
                    patches.add(new Patch(string.end, string.end,
                            " ]".getBytes(StandardCharsets.ISO_8859_1)));
                    patches.add(new Patch(operator.start, operator.end,
                            "TJ".getBytes(StandardCharsets.ISO_8859_1)));
                }
                case "'" -> {
                    patches.add(new Patch(string.start, string.end,
                            ("T* [" + joined).getBytes(StandardCharsets.ISO_8859_1)));
                    patches.add(new Patch(string.end, string.end,
                            " ]".getBytes(StandardCharsets.ISO_8859_1)));
                    patches.add(new Patch(operator.start, operator.end,
                            "TJ".getBytes(StandardCharsets.ISO_8859_1)));
                }
                case "\"" -> {
                    // aw ac (string) "  ->  aw <aw> ac <ac> T* [ (string) adj ] TJ
                    int size = operands.size();
                    if (size >= 3) {
                        Value wordSpacing = operands.get(size - 3);
                        Value charSpacing = operands.get(size - 2);
                        patches.add(new Patch(wordSpacing.start, wordSpacing.end,
                                ("aw " + wordSpacing.raw).getBytes(StandardCharsets.ISO_8859_1)));
                        patches.add(new Patch(charSpacing.start, charSpacing.end,
                                ("ac " + charSpacing.raw).getBytes(StandardCharsets.ISO_8859_1)));
                    }
                    patches.add(new Patch(string.start, string.end,
                            ("T* [" + joined).getBytes(StandardCharsets.ISO_8859_1)));
                    patches.add(new Patch(string.end, string.end,
                            " ]".getBytes(StandardCharsets.ISO_8859_1)));
                    patches.add(new Patch(operator.start, operator.end,
                            "TJ".getBytes(StandardCharsets.ISO_8859_1)));
                }
                default -> {
                    // unreachable - rewrite() is only called for the four
                    // show-text operators above
                }
            }
        }

        if (changed) {
            // Every token that was found is reported as removed, and hits with
            // excess width announce a line shift so later runs on the same
            // baseline can be moved by the caller.
            for (int h = 0; h < hits.size(); h++) {
                String tokenText = full.substring((int) hits.get(h)[0], (int) hits.get(h)[1]);
                removed.add(tokenText);
                if (hitEndX[h] >= 0f) {
                    // every hit is announced (even ones whose value fits) so
                    // the reflow pass can protect the boundaries a drawn
                    // value's ink touches
                    entries.add(new HitShift((String) hits.get(h)[2],
                            hitEndX[h], hitInkEnd[h], hitExtra[h].floatValue()));
                }
            }
        }
        return new RewriteResult(removed, entries, advance, ink, lead);
    }

    /** Per-operator facts: removed tokens plus the run's total advance. */
    private record RewriteResult(Set<String> removed, List<HitShift> entries,
                                 float advance, float inkAdvance,
                                 float leadAdvance) {
    }

    /**
     * A removed-token hit: which token it was, where the deleted token began
     * ({@code relEndX} points after its show-text operator's text origin)
     * and how many points its drawn replacement needs ({@code excess}, zero
     * when the value fits the placeholder's slot).
     */
    private record HitShift(String token, float relEndX, float inkEndX,
                            float excess) {
    }

    private static TextState textState(PDFont font, float fontSize,
                                       float charSpacing, float wordSpacing) {
        return new TextState(font, fontSize, charSpacing, wordSpacing);
    }

    /**
     * Computes the {@code TJ} adjustment that restores the horizontal advance
     * of the removed characters.
     *
     * <p>A {@code TJ} number is expressed in thousandths of a unit of text
     * space, i.e. in the same 1/1000 glyph-space units as the font widths, so
     * subtracting the removed widths cancels the advance. Character and word
     * spacing add to the advance of every removed glyph and have to be
     * compensated as well; horizontal scaling cancels out on both sides.</p>
     */
    private static double adjustmentFor(TextState state, float removedWidth,
                                        int removedChars, int removedSpaces,
                                        float extraShiftPt) {
        double adjustment = -removedWidth;
        if (state.fontSize > 0f) {
            adjustment -= (removedChars * (double) state.charSpacing
                    + removedSpaces * (double) state.wordSpacing) * 1000.0 / state.fontSize;
            // The extra shift moves the rest of the line right by the amount
            // the drawn value is wider than the removed token.
            adjustment -= extraShiftPt * 1000.0 / state.fontSize;
        }
        return adjustment;
    }

    /** The subset of the graphics/text state that affects glyph advance. */
    private record TextState(PDFont font, float fontSize, float charSpacing, float wordSpacing) {
    }

    private static boolean coversWholeString(List<Decoded> chars, int byteLength) {
        if (chars.isEmpty()) {
            return byteLength == 0;
        }
        int expected = 0;
        for (Decoded c : chars) {
            if (c.byteStart != expected) {
                return false;
            }
            expected = c.byteStart + c.byteLength;
        }
        return expected == byteLength;
    }

    // ------------------------------------------------------------------
    // Font decoding
    // ------------------------------------------------------------------

    /**
     * Decodes the bytes of a PDF string with the given font.
     *
     * @return one entry per code, or {@code null} if the string could not be
     *         decoded reliably and must not be touched
     */
    private static List<Decoded> decode(PDFont font, byte[] bytes) {
        List<Decoded> result = new ArrayList<>();
        if (font == null) {
            return bytes.length == 0 ? result : null;
        }
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        int offset = 0;
        while (in.available() > 0) {
            int before = in.available();
            int code;
            try {
                code = font.readCode(in);
            } catch (IOException | RuntimeException e) {
                return null;
            }
            int consumed = before - in.available();
            if (consumed <= 0) {
                return null;
            }
            String unicode;
            float width;
            try {
                unicode = font.toUnicode(code);
                width = font.getWidth(code);
            } catch (IOException | RuntimeException e) {
                return null;
            }
            if (unicode == null) {
                unicode = "";
            }
            result.add(new Decoded(unicode, offset, consumed, width,
                    consumed == 1 && code == 32));
            offset += consumed;
        }
        return result;
    }

    private static PDFont fontFor(PDPage page, String name) {
        if (name == null) {
            return null;
        }
        PDResources resources = page.getResources();
        if (resources == null) {
            return null;
        }
        try {
            return resources.getFont(COSName.getPDFName(name));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Patching
    // ------------------------------------------------------------------

    private static byte[] applyPatches(byte[] content, List<Patch> patches) {
        patches.sort(Comparator.comparingInt(patch -> patch.start));
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 64);
        int position = 0;
        for (Patch patch : patches) {
            if (patch.start < position) {
                throw new IllegalStateException("overlapping content stream patches");
            }
            out.write(content, position, patch.start - position);
            out.write(patch.replacement, 0, patch.replacement.length);
            position = patch.end;
        }
        out.write(content, position, content.length - position);
        return out.toByteArray();
    }

    private static String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2 + 2);
        sb.append('<');
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        sb.append('>');
        return sb.toString();
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e9) {
            return Long.toString((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }

    // ------------------------------------------------------------------
    // Content stream scanning
    // ------------------------------------------------------------------

    private enum Kind { STRING, NUMBER, NAME, ARRAY }

    private static final class Value {
        final Kind kind;
        final int start;
        final int end;
        final String raw;
        byte[] bytes;
        double number;
        String name;
        List<Value> elements = List.of();

        Value(Kind kind, int start, int end, String raw) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.raw = raw;
        }
    }

    private static final class Operator {
        final String name;
        final int start;
        final int end;

        Operator(String name, int start, int end) {
            this.name = name;
            this.start = start;
            this.end = end;
        }
    }

    private record Patch(int start, int end, byte[] replacement) {
    }

    private static final class Decoded {
        final String unicode;
        final int byteStart;
        final int byteLength;
        final float width;
        /** Word spacing applies to single-byte code 32 only (PDF 32000-1, 9.3.3). */
        final boolean wordSpacingApplies;
        int textStart;
        int textEnd;

        Decoded(String unicode, int byteStart, int byteLength, float width,
                boolean wordSpacingApplies) {
            this.unicode = unicode;
            this.byteStart = byteStart;
            this.byteLength = byteLength;
            this.width = width;
            this.wordSpacingApplies = wordSpacingApplies;
        }
    }

    /** Scans a content stream into operands and operators, keeping byte offsets. */
    private static List<Object> scan(byte[] buffer) {
        List<Object> items = new ArrayList<>();
        int i = 0;
        while (i < buffer.length) {
            int c = buffer[i] & 0xFF;
            if (isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '%') {
                while (i < buffer.length && buffer[i] != '\n' && buffer[i] != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '[') {
                Value value = scanArray(buffer, i);
                items.add(value);
                i = value.end;
                continue;
            }
            if (c == '<' && i + 1 < buffer.length && buffer[i + 1] == '<') {
                i = skipDictionary(buffer, i);
                continue;
            }
            if (c == '<') {
                Value value = scanHexString(buffer, i);
                items.add(value);
                i = value.end;
                continue;
            }
            if (c == '(') {
                Value value = scanLiteralString(buffer, i);
                items.add(value);
                i = value.end;
                continue;
            }
            if (c == '/') {
                Value value = scanName(buffer, i);
                items.add(value);
                i = value.end;
                continue;
            }
            if (c == ']' || c == '>' || c == '}' || c == '{') {
                i++;
                continue;
            }
            if (isRegular(c)) {
                int start = i;
                while (i < buffer.length && isRegular(buffer[i] & 0xFF)) {
                    i++;
                }
                String word = new String(buffer, start, i - start, StandardCharsets.ISO_8859_1);
                if (looksNumeric(word)) {
                    Value value = new Value(Kind.NUMBER, start, i, word);
                    try {
                        value.number = Double.parseDouble(word);
                    } catch (NumberFormatException e) {
                        value.number = 0;
                    }
                    items.add(value);
                } else {
                    items.add(new Operator(word, start, i));
                }
                continue;
            }
            i++;
        }
        return items;
    }

    private static Value scanArray(byte[] buffer, int start) {
        List<Value> elements = new ArrayList<>();
        int i = start + 1;
        while (i < buffer.length) {
            int c = buffer[i] & 0xFF;
            if (isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '%') {
                while (i < buffer.length && buffer[i] != '\n' && buffer[i] != '\r') {
                    i++;
                }
                continue;
            }
            if (c == ']') {
                i++;
                break;
            }
            if (c == '[') {
                Value value = scanArray(buffer, i);
                elements.add(value);
                i = value.end;
                continue;
            }
            if (c == '<') {
                Value value = scanHexString(buffer, i);
                elements.add(value);
                i = value.end;
                continue;
            }
            if (c == '(') {
                Value value = scanLiteralString(buffer, i);
                elements.add(value);
                i = value.end;
                continue;
            }
            if (c == '/') {
                Value value = scanName(buffer, i);
                elements.add(value);
                i = value.end;
                continue;
            }
            if (isRegular(c)) {
                int wordStart = i;
                while (i < buffer.length && isRegular(buffer[i] & 0xFF)) {
                    i++;
                }
                String word = new String(buffer, wordStart, i - wordStart, StandardCharsets.ISO_8859_1);
                Value value = new Value(Kind.NUMBER, wordStart, i, word);
                try {
                    value.number = Double.parseDouble(word);
                } catch (NumberFormatException e) {
                    value.number = 0;
                }
                elements.add(value);
                continue;
            }
            i++;
        }
        Value value = new Value(Kind.ARRAY, start, i,
                new String(buffer, start, i - start, StandardCharsets.ISO_8859_1));
        value.elements = elements;
        return value;
    }

    private static Value scanHexString(byte[] buffer, int start) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = start + 1;
        int high = -1;
        while (i < buffer.length) {
            int c = buffer[i] & 0xFF;
            if (c == '>') {
                i++;
                break;
            }
            int digit = hexValue(c);
            if (digit >= 0) {
                if (high < 0) {
                    high = digit;
                } else {
                    out.write((high << 4) | digit);
                    high = -1;
                }
            }
            i++;
        }
        if (high >= 0) {
            out.write(high << 4);
        }
        Value value = new Value(Kind.STRING, start, i,
                new String(buffer, start, i - start, StandardCharsets.ISO_8859_1));
        value.bytes = out.toByteArray();
        return value;
    }

    private static Value scanLiteralString(byte[] buffer, int start) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = start + 1;
        int depth = 1;
        while (i < buffer.length) {
            int c = buffer[i] & 0xFF;
            if (c == '\\') {
                i++;
                if (i >= buffer.length) {
                    break;
                }
                int escaped = buffer[i] & 0xFF;
                switch (escaped) {
                    case 'n' -> { out.write('\n'); i++; }
                    case 'r' -> { out.write('\r'); i++; }
                    case 't' -> { out.write('\t'); i++; }
                    case 'b' -> { out.write('\b'); i++; }
                    case 'f' -> { out.write('\f'); i++; }
                    case '(' -> { out.write('('); i++; }
                    case ')' -> { out.write(')'); i++; }
                    case '\\' -> { out.write('\\'); i++; }
                    case '\r' -> {
                        i++;
                        if (i < buffer.length && buffer[i] == '\n') {
                            i++;
                        }
                    }
                    case '\n' -> i++;
                    default -> {
                        if (escaped >= '0' && escaped <= '7') {
                            int value = 0;
                            int digits = 0;
                            while (digits < 3 && i < buffer.length) {
                                int d = buffer[i] & 0xFF;
                                if (d < '0' || d > '7') {
                                    break;
                                }
                                value = value * 8 + (d - '0');
                                i++;
                                digits++;
                            }
                            out.write(value & 0xFF);
                        } else {
                            out.write(escaped);
                            i++;
                        }
                    }
                }
                continue;
            }
            if (c == '(') {
                depth++;
                out.write(c);
                i++;
                continue;
            }
            if (c == ')') {
                depth--;
                if (depth == 0) {
                    i++;
                    break;
                }
                out.write(c);
                i++;
                continue;
            }
            out.write(c);
            i++;
        }
        Value value = new Value(Kind.STRING, start, i,
                new String(buffer, start, i - start, StandardCharsets.ISO_8859_1));
        value.bytes = out.toByteArray();
        return value;
    }

    private static Value scanName(byte[] buffer, int start) {
        int i = start + 1;
        while (i < buffer.length && isRegular(buffer[i] & 0xFF)) {
            i++;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = start + 1; k < i; k++) {
            if (buffer[k] == '#' && k + 2 < i) {
                int high = hexValue(buffer[k + 1] & 0xFF);
                int low = hexValue(buffer[k + 2] & 0xFF);
                if (high >= 0 && low >= 0) {
                    sb.append((char) ((high << 4) | low));
                    k += 2;
                    continue;
                }
            }
            sb.append((char) (buffer[k] & 0xFF));
        }
        Value value = new Value(Kind.NAME, start, i,
                new String(buffer, start, i - start, StandardCharsets.ISO_8859_1));
        value.name = sb.toString();
        return value;
    }

    private static int skipDictionary(byte[] buffer, int start) {
        int i = start + 2;
        int depth = 1;
        while (i < buffer.length && depth > 0) {
            int c = buffer[i] & 0xFF;
            if (c == '<' && i + 1 < buffer.length && buffer[i + 1] == '<') {
                i = skipDictionary(buffer, i);
                continue;
            }
            if (c == '>' && i + 1 < buffer.length && buffer[i + 1] == '>') {
                depth--;
                i += 2;
                continue;
            }
            if (c == '(') {
                i = scanLiteralString(buffer, i).end;
                continue;
            }
            if (c == '<') {
                i = scanHexString(buffer, i).end;
                continue;
            }
            if (c == '/') {
                i = scanName(buffer, i).end;
                continue;
            }
            i++;
        }
        return i;
    }

    private static boolean isWhitespace(int c) {
        return c == 0x00 || c == 0x09 || c == 0x0A || c == 0x0C || c == 0x0D || c == 0x20;
    }

    private static boolean isRegular(int c) {
        return !isWhitespace(c)
                && c != '(' && c != ')' && c != '<' && c != '>'
                && c != '[' && c != ']' && c != '{' && c != '}'
                && c != '/' && c != '%';
    }

    private static boolean looksNumeric(String word) {
        if (word.isEmpty()) {
            return false;
        }
        char c = word.charAt(0);
        return c == '+' || c == '-' || c == '.' || (c >= '0' && c <= '9');
    }

    private static int hexValue(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
