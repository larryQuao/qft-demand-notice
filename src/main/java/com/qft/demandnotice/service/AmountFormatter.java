package com.qft.demandnotice.service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;

/**
 * Parses and formats Ghana Cedi amounts for the demand letter,
 * e.g. {@code GHS 1,389.00}.
 */
public final class AmountFormatter {

    public static final String CURRENCY_PREFIX = "GHS ";

    private static final DecimalFormat DISPLAY_FORMAT;

    static {
        // Force US-style symbols so we always get "1,389.00" regardless of JVM locale.
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        DISPLAY_FORMAT = new DecimalFormat("#,##0.00", symbols);
        DISPLAY_FORMAT.setParseBigDecimal(true);
    }

    private AmountFormatter() {
    }

    /**
     * Formats an amount with a thousands separator and exactly two decimal places,
     * prefixed with "GHS ", e.g. {@code GHS 1,389.00}.
     */
    public static String format(BigDecimal amount) {
        return CURRENCY_PREFIX + DISPLAY_FORMAT.format(amount);
    }

    /**
     * Parses user input such as "1389", "1389.00" or "1,389.00" into a {@link BigDecimal}.
     *
     * @return the parsed amount, or empty if the input is blank, not numeric,
     *         not greater than zero, or has more than two decimal places
     */
    public static Optional<BigDecimal> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String cleaned = input.trim().replace(",", "");
        if (cleaned.isEmpty() || !cleaned.matches("\\d+(\\.\\d{1,2})?")) {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal(cleaned);
        if (value.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
