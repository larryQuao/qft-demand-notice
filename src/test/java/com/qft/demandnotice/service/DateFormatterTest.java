package com.qft.demandnotice.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DateFormatterTest {

    @Test
    void ordinalSuffixes_coverAllCases() {
        assertEquals("st", DateFormatter.ordinalSuffix(1));
        assertEquals("nd", DateFormatter.ordinalSuffix(2));
        assertEquals("rd", DateFormatter.ordinalSuffix(3));
        assertEquals("th", DateFormatter.ordinalSuffix(4));
        assertEquals("th", DateFormatter.ordinalSuffix(11));
        assertEquals("th", DateFormatter.ordinalSuffix(12));
        assertEquals("th", DateFormatter.ordinalSuffix(13));
        assertEquals("st", DateFormatter.ordinalSuffix(21));
        assertEquals("nd", DateFormatter.ordinalSuffix(22));
        assertEquals("rd", DateFormatter.ordinalSuffix(23));
        assertEquals("th", DateFormatter.ordinalSuffix(24));
        assertEquals("st", DateFormatter.ordinalSuffix(31));
    }

    @Test
    void ordinalSuffix_rejectsInvalidDays() {
        assertThrows(IllegalArgumentException.class, () -> DateFormatter.ordinalSuffix(0));
        assertThrows(IllegalArgumentException.class, () -> DateFormatter.ordinalSuffix(32));
    }

    @Test
    void formatLetterDate_usesPlanSampleValues() {
        LocalDate letterDate = LocalDate.of(2026, Month.SEPTEMBER, 24);
        assertEquals("24th September, 2026", DateFormatter.formatLetterDate(letterDate));

        LocalDate deadline = LocalDate.of(2026, Month.OCTOBER, 14);
        assertEquals("14th October, 2026", DateFormatter.formatLetterDate(deadline));
    }

    @Test
    void formatContributionPeriod_usesPlanSampleValue() {
        YearMonth period = YearMonth.of(2026, Month.SEPTEMBER);
        assertEquals("September, 2026", DateFormatter.formatContributionPeriod(period));
    }

    @Test
    void monthName_returnsFullEnglishName() {
        assertEquals("January", DateFormatter.monthName(Month.JANUARY));
        assertEquals("December", DateFormatter.monthName(Month.DECEMBER));
    }
}
