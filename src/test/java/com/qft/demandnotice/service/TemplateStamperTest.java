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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // Note: text extraction also reads the original (white-out covered) token
        // text underneath, so only positive checks are meaningful here.
        assertTrue(text.contains("24th September, 2026"));
        assertTrue(text.contains("September, 2026"));
        assertTrue(text.contains("GHS 1,389.00"));
        assertTrue(text.contains("14th October, 2026"));
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
}
