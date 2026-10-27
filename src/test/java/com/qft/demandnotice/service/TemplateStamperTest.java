package com.qft.demandnotice.service;

import com.qft.demandnotice.model.DemandNoticeData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateStamperTest {

    private static final PDType1Font FONT =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private static final DemandNoticeData SAMPLE = new DemandNoticeData(
            LocalDate.of(2026, Month.SEPTEMBER, 24),
            YearMonth.of(2026, Month.SEPTEMBER),
            new BigDecimal("1389.00"),
            LocalDate.of(2026, Month.OCTOBER, 14));

    @Test
    void stamp_replacesAllTokensWithFormValues() throws Exception {
        byte[] template = pdfWithLines(
                "RE: DEMAND NOTICE",
                "Letter date: {{LETTER_DATE}}",
                "Period: {{PERIOD}}",
                "Amount: {{AMOUNT}}",
                "Deadline: {{DEADLINE}}");

        TemplateStamper.StampOutcome outcome = TemplateStamper.stamp(template, SAMPLE);

        assertEquals(List.of("{{LETTER_DATE}}", "{{PERIOD}}", "{{AMOUNT}}", "{{DEADLINE}}"),
                outcome.replacedTokens());
        assertTrue(outcome.unreplacedTokens().isEmpty());

        String text = extractText(outcome.pdfBytes());
        // The placeholders are deleted from the content stream, so they must not
        // show up in the text layer any more.
        assertFalse(text.contains("{{LETTER_DATE}}"), text);
        assertFalse(text.contains("{{PERIOD}}"), text);
        assertFalse(text.contains("{{AMOUNT}}"), text);
        assertFalse(text.contains("{{DEADLINE}}"), text);
        assertTrue(text.contains("24th September, 2026"));
        assertTrue(text.contains("September, 2026"));
        assertTrue(text.contains("GHS 1,389.00"));
        assertTrue(text.contains("14th October, 2026"));
    }

    @Test
    void stamp_keepsTheSurroundingTextInPlace() throws Exception {
        byte[] template = pdfWithLines(
                "RE: DEMAND NOTICE",
                "Amount: {{AMOUNT}} payable at the branch");

        float tokenX = xOfFirstOccurrence(template, '{');
        float tailX = xOfFirstOccurrence(template, 'p');

        TemplateStamper.StampOutcome outcome = TemplateStamper.stamp(template, SAMPLE);

        // The value is drawn where the placeholder used to be, at the body's
        // own font size - values are never shrunk.
        float valueX = xOfFirstOccurrence(outcome.pdfBytes(), 'G');
        assertEquals(tokenX, valueX, 0.5f);
        assertEquals(12f, fontSizeAt(outcome.pdfBytes(), 'G'), 0.01f);

        // The text after the placeholder only moves as far as it must to leave
        // room for the value: when the value is wider than the token (it never
        // is shrunk), the rest of the line is shifted right by exactly the
        // excess, so the value still reads like the surrounding body text.
        float tokenWidth = FONT.getStringWidth("{{AMOUNT}}") / 1000f * 12f;
        float valueWidth = FONT.getStringWidth("GHS 1,389.00") / 1000f * 12f;
        float tailXAfter = xOfFirstOccurrence(outcome.pdfBytes(), 'p');
        assertEquals(tailX + Math.max(0f, valueWidth - tokenWidth), tailXAfter, 0.6f);
    }

    @Test
    void stamp_reportsTokensThatAreMissingFromTemplate() throws Exception {
        byte[] template = pdfWithLines("Amount: {{AMOUNT}}", "No other tokens here.");

        TemplateStamper.StampOutcome outcome = TemplateStamper.stamp(template, SAMPLE);

        assertEquals(List.of("{{AMOUNT}}"), outcome.replacedTokens());
        assertEquals(List.of("{{LETTER_DATE}}", "{{PERIOD}}", "{{DEADLINE}}"),
                outcome.unreplacedTokens());
    }

    @Test
    void stamp_withoutAnyTokens_passesTemplateThrough() throws Exception {
        byte[] template = pdfWithLines("This template has no tokens.");

        TemplateStamper.StampOutcome outcome = TemplateStamper.stamp(template, SAMPLE);

        assertTrue(outcome.replacedTokens().isEmpty());
        assertEquals(4, outcome.unreplacedTokens().size());
        assertTrue(outcome.pdfBytes().length > 0);
        String text = extractText(outcome.pdfBytes());
        assertTrue(text.contains("This template has no tokens."));
    }

    // ------------------------------------------------------------------

    private static byte[] pdfWithLines(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(FONT, 12);
                cs.beginText();
                cs.newLineAtOffset(60, 780);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -20);
                }
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static String extractText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /**
     * Font size the given character is drawn at, taken from its left-most
     * occurrence. Used to verify that stamped values keep the body text size.
     */
    private static float fontSizeAt(byte[] pdf, char ch) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float[] leftmost = {Float.MAX_VALUE};
            float[] size = {-1f};
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    String unicode = text.getUnicode();
                    if (unicode != null && unicode.indexOf(ch) >= 0
                            && text.getXDirAdj() < leftmost[0]) {
                        leftmost[0] = text.getXDirAdj();
                        size[0] = text.getFontSizeInPt();
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.writeText(doc, new StringWriter());
            assertTrue(leftmost[0] < Float.MAX_VALUE,
                    "character '" + ch + "' was not found in the PDF");
            return size[0];
        }
    }

    /**
     * Left-most x coordinate of the given character in the PDF's text layer.
     * Used to verify that stamping does not move the surrounding text.
     */
    private static float xOfFirstOccurrence(byte[] pdf, char ch) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            float[] leftmost = {Float.MAX_VALUE};
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    String unicode = text.getUnicode();
                    if (unicode != null && unicode.indexOf(ch) >= 0) {
                        leftmost[0] = Math.min(leftmost[0], text.getXDirAdj());
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.writeText(doc, new StringWriter());
            assertTrue(leftmost[0] < Float.MAX_VALUE,
                    "character '" + ch + "' was not found in the PDF");
            return leftmost[0];
        }
    }
}
