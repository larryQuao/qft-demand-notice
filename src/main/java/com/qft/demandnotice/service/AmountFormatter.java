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

    /**
     * {@link DecimalFormat} is not thread-safe, and formatting happens on both
     * the JavaFX thread and the background generation thread, so each thread
     * gets its own instance.
     */
    private static final ThreadLocal<DecimalFormat> DISPLAY_FORMAT =
            ThreadLocal.withInitial(() -> {
                // Force US-style symbols so we always get "1,389.00" regardless
                // of the JVM locale.
                DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
                DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
                format.setParseBigDecimal(true);
                return format;
            });

    private AmountFormatter() {
    }

    /**
     * Formats an amount with a thousands separator and exactly two decimal places,
     * prefixed with "GHS ", e.g. {@code GHS 1,389.00}.
     */
    public static String format(BigDecimal amount) {
        return CURRENCY_PREFIX + DISPLAY_FORMAT.get().format(amount);
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
