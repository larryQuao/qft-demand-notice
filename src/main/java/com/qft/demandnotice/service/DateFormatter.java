package com.qft.demandnotice.service;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Formats dates for the demand letter: ordinal day numbers
 * ("1st", "2nd", "3rd", "24th"), full month names and combined
 * letter-style dates such as "24th September, 2026".
 */
public final class DateFormatter {

    private DateFormatter() {
    }

    /**
     * Returns the ordinal suffix for a day-of-month: "st", "nd", "rd" or "th".
     * Teens (11th-13th) always use "th".
     */
    public static String ordinalSuffix(int day) {
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException("day must be between 1 and 31: " + day);
        }
        if (day >= 11 && day <= 13) {
            return "th";
        }
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /** Formats a full date as used for the letter date and deadline, e.g. "24th September, 2026". */
    public static String formatLetterDate(LocalDate date) {
        return date.getDayOfMonth() + ordinalSuffix(date.getDayOfMonth())
                + " " + monthName(date.getMonth()) + ", " + date.getYear();
    }

    /** Formats a contribution period as used in the subject and body, e.g. "September, 2026". */
    public static String formatContributionPeriod(YearMonth period) {
        return monthName(period.getMonth()) + ", " + period.getYear();
    }

    /** Full English month name, e.g. "September". */
    public static String monthName(Month month) {
        return month.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
