package com.qft.demandnotice.service;

import com.qft.demandnotice.model.DemandNoticeData;
import com.qft.demandnotice.util.Constants;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link DemandNoticeData} to an A4 PDF that mirrors the QFT
 * demand letter layout: logo and head office address in the header, recipient
 * block, subject line, body paragraphs, signature block and the blue footer
 * rule with the Board of Directors line.
 */
public final class PdfGenerator {

    private static final PDType1Font FONT_REGULAR =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font FONT_BOLD =
            new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private PdfGenerator() {
    }

    /**
     * Generates the demand notice PDF.
     *
     * @param data notice data (variable fields)
     * @param out  target file; parent directories are created if missing
     * @throws IOException if the file cannot be written
     */
    public static void generate(DemandNoticeData data, Path out) throws IOException {
        Files.createDirectories(out.getParent());

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawLogo(doc, cs);
                drawAddressBlock(cs);
                drawLetterDate(cs, data);
                drawRecipient(cs);
                drawSalutationAndSubject(cs, data);
                float bodyEndY = drawBody(cs, data);
                drawSignature(cs, bodyEndY);
                drawFooter(cs);
            }

            doc.save(out.toFile());
        }
    }

    // ------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------

    private static void drawLogo(PDDocument doc, PDPageContentStream cs) throws IOException {
        byte[] logoBytes;
        try (InputStream in = PdfGenerator.class.getResourceAsStream(Constants.LOGO_RESOURCE)) {
            if (in == null) {
                return; // no logo available yet - letterhead text still renders
            }
            logoBytes = in.readAllBytes();
        }
        PDImageXObject logo = PDImageXObject.createFromByteArray(doc, logoBytes, "qft_logo");
        float aspect = (float) logo.getHeight() / logo.getWidth();
        float height = Constants.LOGO_WIDTH_PT * aspect;
        float y = Constants.HEADER_TOP_Y - height;
        cs.drawImage(logo, Constants.MARGIN_LEFT, y, Constants.LOGO_WIDTH_PT, height);
    }

    private static void drawAddressBlock(PDPageContentStream cs) throws IOException {
        cs.setFont(FONT_REGULAR, Constants.FONT_SIZE_ADDRESS);
        cs.setNonStrokingColor(Constants.TEXT_BLACK);
        float x = Constants.PAGE_WIDTH - Constants.MARGIN_RIGHT;
        float y = Constants.ADDRESS_TOP_Y;
        for (String line : Constants.COMPANY_ADDRESS_LINES) {
            writeRightAligned(cs, line, x, y, FONT_REGULAR, Constants.FONT_SIZE_ADDRESS);
            y -= Constants.ADDRESS_LINE_HEIGHT;
        }
    }

    // ------------------------------------------------------------------
    // Letter content
    // ------------------------------------------------------------------

    private static void drawLetterDate(PDPageContentStream cs, DemandNoticeData data) throws IOException {
        cs.setFont(FONT_REGULAR, Constants.FONT_SIZE_BODY);
        cs.setNonStrokingColor(Constants.TEXT_BLACK);
        writeLine(cs, DateFormatter.formatLetterDate(data.letterDate()),
                Constants.MARGIN_LEFT, Constants.LETTER_DATE_Y);
    }

    private static void drawRecipient(PDPageContentStream cs) throws IOException {
        cs.setFont(FONT_REGULAR, Constants.FONT_SIZE_BODY);
        cs.setNonStrokingColor(Constants.TEXT_BLACK);
        float y = Constants.RECIPIENT_Y;
        for (String line : new String[]{Constants.RECIPIENT_LINE_1, Constants.RECIPIENT_LINE_2,
                Constants.RECIPIENT_LINE_3, Constants.RECIPIENT_LINE_4}) {
            writeLine(cs, line, Constants.MARGIN_LEFT, y);
            y -= Constants.RECIPIENT_LINE_HEIGHT;
        }
    }

    private static void drawSalutationAndSubject(PDPageContentStream cs, DemandNoticeData data)
            throws IOException {
        cs.setFont(FONT_REGULAR, Constants.FONT_SIZE_BODY);
        cs.setNonStrokingColor(Constants.TEXT_BLACK);
        writeLine(cs, Constants.SALUTATION, Constants.MARGIN_LEFT, Constants.SALUTATION_Y);

        String subject = Constants.SUBJECT_TEMPLATE
                .formatted(DateFormatter.formatContributionPeriod(data.contributionMonth()));
        writeBoldUnderlined(cs, subject, Constants.MARGIN_LEFT, Constants.SUBJECT_Y,
                Constants.FONT_SIZE_SUBJECT);
    }

    private static float drawBody(PDPageContentStream cs, DemandNoticeData data) throws IOException {
        String period = DateFormatter.formatContributionPeriod(data.contributionMonth());
        String amount = AmountFormatter.format(data.amount());
        String deadline = DateFormatter.formatLetterDate(data.deadline());

        String[] paragraphs = {
                Constants.BODY_TEMPLATES[0],
                Constants.BODY_TEMPLATES[1].formatted(amount, period),
                Constants.BODY_TEMPLATES[2].formatted(amount, deadline),
                Constants.BODY_TEMPLATES[3]
        };

        cs.setFont(FONT_REGULAR, Constants.FONT_SIZE_BODY);
        cs.setNonStrokingColor(Constants.TEXT_BLACK);
        float y = Constants.BODY_TOP_Y;
        for (String paragraph : paragraphs) {
            for (String line : wrap(paragraph, FONT_REGULAR, Constants.FONT_SIZE_BODY,
                    Constants.CONTENT_WIDTH)) {
                writeLine(cs, line, Constants.MARGIN_LEFT, y);
                y -= Constants.BODY_LINE_HEIGHT;
            }
            y -= Constants.PARAGRAPH_GAP;
        }
        return y;
    }

    // ------------------------------------------------------------------
    // Signature and footer
    // ------------------------------------------------------------------

    private static void drawSignature(PDPageContentStream cs, float bodyEndY) throws IOException {
        float y = bodyEndY - Constants.SIGNATURE_GAP;
        cs.setFont(FONT_REGULAR, Constants.FONT_SIZE_BODY);
        cs.setNonStrokingColor(Constants.TEXT_BLACK);

        writeLine(cs, Constants.COMPLIMENTARY_CLOSE, Constants.MARGIN_LEFT, y);
        y -= Constants.SIGNATURE_LINE_HEIGHT;
        writeLine(cs, Constants.SIGNED_LABEL, Constants.MARGIN_LEFT, y);
        y -= Constants.SIGNATURE_SPACE;
        writeLine(cs, Constants.SIGNATORY_NAME, Constants.MARGIN_LEFT, y);
        y -= Constants.SIGNATURE_LINE_HEIGHT;
        writeLine(cs, Constants.SIGNATORY_TITLE, Constants.MARGIN_LEFT, y);
    }

    private static void drawFooter(PDPageContentStream cs) throws IOException {
        // Blue horizontal rule
        cs.setStrokingColor(Constants.FOOTER_BLUE);
        cs.setLineWidth(1.2f);
        cs.moveTo(Constants.MARGIN_LEFT, Constants.FOOTER_LINE_Y);
        cs.lineTo(Constants.PAGE_WIDTH - Constants.MARGIN_RIGHT, Constants.FOOTER_LINE_Y);
        cs.stroke();

        // Centred board line
        cs.setFont(FONT_REGULAR, Constants.FONT_SIZE_FOOTER);
        cs.setNonStrokingColor(Constants.FOOTER_BLUE);
        writeCentered(cs, Constants.BOARD_LINE, Constants.PAGE_WIDTH / 2f, Constants.FOOTER_TEXT_Y);
    }

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    private static void writeLine(PDPageContentStream cs, String text, float x, float y)
            throws IOException {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static void writeRightAligned(PDPageContentStream cs, String text, float rightX, float y,
                                          PDType1Font font, float fontSize) throws IOException {
        float width = textWidth(font, fontSize, text);
        writeLine(cs, text, rightX - width, y);
    }

    private static void writeCentered(PDPageContentStream cs, String text, float centerX, float y)
            throws IOException {
        float width = textWidth(FONT_REGULAR, Constants.FONT_SIZE_FOOTER, text);
        writeLine(cs, text, centerX - width / 2f, y);
    }

    private static void writeBoldUnderlined(PDPageContentStream cs, String text, float x, float y,
                                            float fontSize) throws IOException {
        cs.setFont(FONT_BOLD, fontSize);
        writeLine(cs, text, x, y);

        float textWidth = textWidth(FONT_BOLD, fontSize, text);
        float underlineY = y - 1.5f;
        cs.setStrokingColor(Constants.TEXT_BLACK);
        cs.setLineWidth(0.6f);
        cs.moveTo(x, underlineY);
        cs.lineTo(x + textWidth, underlineY);
        cs.stroke();
    }

    private static float textWidth(PDType1Font font, float fontSize, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * fontSize;
    }

    /**
     * Greedy word-wrap of {@code text} so that no rendered line exceeds
     * {@code maxWidth} points.
     */
    static List<String> wrap(String text, PDType1Font font, float fontSize, float maxWidth)
            throws IOException {
        List<String> lines = new ArrayList<>();
        for (String hardLine : text.split("\n")) {
            String[] words = hardLine.split(" ");
            StringBuilder current = new StringBuilder();
            for (String word : words) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (textWidth(font, fontSize, candidate) <= maxWidth) {
                    current = new StringBuilder(candidate);
                } else {
                    if (!current.isEmpty()) {
                        lines.add(current.toString());
                    }
                    current = new StringBuilder(word);
                }
            }
            lines.add(current.toString());
        }
        return lines;
    }

    /** Wrap that rethrows {@link IOException} as unchecked (test/convenience paths). */
    static List<String> wrapUnchecked(String text, PDType1Font font, float fontSize, float maxWidth) {
        try {
            return wrap(text, font, fontSize, maxWidth);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
