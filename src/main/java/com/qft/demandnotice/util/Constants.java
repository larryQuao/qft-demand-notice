package com.qft.demandnotice.util;

import java.awt.Color;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * All fixed content and layout constants for the demand notice.
 *
 * <p>NOTE: Text blocks marked PLACEHOLDER were reconstructed from the plan and
 * must be replaced with the exact wording from the original sample PDF once it
 * is supplied. Layout coordinates are best-effort approximations for A4 and
 * should be tuned during the visual fidelity pass against the sample.</p>
 */
public final class Constants {

    private Constants() {
    }

    // ------------------------------------------------------------------
    // Branding
    // ------------------------------------------------------------------
    public static final Color BRAND_ORANGE = new Color(0xE8, 0x77, 0x22);
    public static final Color FOOTER_BLUE = new Color(0x1F, 0x4E, 0x9C);
    public static final Color TEXT_BLACK = Color.BLACK;

    /** Logo on the classpath (src/main/resources/assets). Replace with the real QFT logo. */
    public static final String LOGO_RESOURCE = "/assets/qft_logo.png";

    // ------------------------------------------------------------------
    // Page geometry (PDF points; A4 = 595.28 x 841.89 pt)
    // ------------------------------------------------------------------
    public static final float PAGE_WIDTH = 595.28f;
    public static final float PAGE_HEIGHT = 841.89f;
    public static final float MARGIN_LEFT = 56f;
    public static final float MARGIN_RIGHT = 56f;
    public static final float CONTENT_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;

    public static final float LOGO_WIDTH_PT = 140f;
    public static final float HEADER_TOP_Y = PAGE_HEIGHT - 40f;   // top edge of logo
    public static final float ADDRESS_TOP_Y = PAGE_HEIGHT - 52f;  // first address baseline
    public static final float ADDRESS_LINE_HEIGHT = 11f;

    public static final float LETTER_DATE_Y = PAGE_HEIGHT - 175f;
    public static final float RECIPIENT_Y = PAGE_HEIGHT - 210f;
    public static final float RECIPIENT_LINE_HEIGHT = 14f;
    public static final float SALUTATION_Y = PAGE_HEIGHT - 282f;
    public static final float SUBJECT_Y = PAGE_HEIGHT - 306f;
    public static final float BODY_TOP_Y = PAGE_HEIGHT - 334f;
    public static final float BODY_LINE_HEIGHT = 15f;
    public static final float PARAGRAPH_GAP = 9f;
    public static final float SIGNATURE_GAP = 34f;   // space between last body line and "Yours Faithfully,"
    public static final float SIGNATURE_LINE_HEIGHT = 16f;
    public static final float SIGNATURE_SPACE = 34f; // vertical room for the handwritten signature

    public static final float FOOTER_LINE_Y = 70f;
    public static final float FOOTER_TEXT_Y = 54f;

    // ------------------------------------------------------------------
    // Typography (PDF core fonts; swap for embedded TTFs if the sample
    // requires it during the fidelity pass)
    // ------------------------------------------------------------------
    public static final float FONT_SIZE_ADDRESS = 9f;
    public static final float FONT_SIZE_BODY = 11f;
    public static final float FONT_SIZE_SUBJECT = 11f;
    public static final float FONT_SIZE_FOOTER = 8f;
    public static final float FONT_SIZE_SIGNATORY = 11f;

    // ------------------------------------------------------------------
    // Fixed text blocks
    // ------------------------------------------------------------------

    /** PLACEHOLDER: head office address block (top-right of letterhead). */
    public static final String[] COMPANY_ADDRESS_LINES = {
            "QLAC FINANCIAL TRUST",
            "Head Office - Accra, Ghana",
            "P.O. Box AN 12345, Accra-North",
            "Tel: +233 (0) 30 000 0000",
            "Email: info@qlacfinancialtrust.com.gh"
    };

    /** Recipient block (fixed for the current version). */
    public static final String RECIPIENT_LINE_1 = "NorthCode Ghana";
    public static final String RECIPIENT_LINE_2 = "Attn: Cletus Zume";
    public static final String RECIPIENT_LINE_3 = "P.O. Box 78";
    public static final String RECIPIENT_LINE_4 = "Damango";

    public static final String SALUTATION = "Dear Sir/Madam,";

    /**
     * Subject line. %s receives the contribution period, e.g. "September, 2026".
     * PLACEHOLDER wording - align with the sample.
     */
    public static final String SUBJECT_TEMPLATE =
            "RE: DEMAND FOR TIER 2 PENSION CONTRIBUTIONS FOR THE MONTH OF %s";

    /**
     * Body paragraphs. Paragraph 2 takes (amount, period); paragraph 3 takes
     * (amount, deadline); paragraphs 1 and 4 take no arguments, e.g.
     * "GHS 1,389.00 for the month of September, 2026".
     * PLACEHOLDER wording - align with the sample.
     */
    public static final String[] BODY_TEMPLATES = {
            "We refer to our previous correspondence on the above subject matter and wish to remind you "
                    + "of your statutory obligation to remit Tier 2 pension contributions in respect of "
                    + "your employees.",

            "Our records show that Tier 2 pension contributions of %s for the month of %s remain unpaid.",

            "You are hereby requested to remit the said amount of %s to Qlac Financial Trust on or before "
                    + "%s. Payment should be made to the Trust's designated bank account and proof of payment "
                    + "forwarded to our office.",

            "Please treat this matter with the urgency it deserves. Failure to comply will leave us with no "
                    + "option but to report the matter to the National Pensions Regulatory Authority (NPRA) "
                    + "and to take steps to recover the amount without further notice to you."
    };

    public static final String COMPLIMENTARY_CLOSE = "Yours Faithfully,";
    public static final String SIGNED_LABEL = "Signed";
    public static final String SIGNATORY_NAME = "Thomas D. Appiah";
    public static final String SIGNATORY_TITLE = "Chief Executive Officer";

    /** PLACEHOLDER: Board of Directors line printed under the blue footer rule. */
    public static final String BOARD_LINE =
            "Board of Directors: [Chairman]  |  [Member]  |  [Member]  |  Thomas D. Appiah (Chief Executive Officer)";

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------
    public static final String OUTPUT_FOLDER_NAME = "QFT Demand Notices";
    public static final String FILENAME_TEMPLATE = "Demand_Notice_NorthCode_%s%d.pdf";

    /** Default output directory: {@code <Documents>/QFT Demand Notices}. */
    public static Path defaultOutputDir() {
        return Paths.get(System.getProperty("user.home"), "Documents", OUTPUT_FOLDER_NAME);
    }
}
