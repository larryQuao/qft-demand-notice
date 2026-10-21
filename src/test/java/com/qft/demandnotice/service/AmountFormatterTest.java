package com.qft.demandnotice.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmountFormatterTest {

    @Test
    void format_matchesPlanSampleValue() {
        assertEquals("GHS 1,389.00", AmountFormatter.format(new BigDecimal("1389.00")));
    }

    @Test
    void format_addsThousandSeparatorsAndTwoDecimals() {
        assertEquals("GHS 0.50", AmountFormatter.format(new BigDecimal("0.5")));
        assertEquals("GHS 1,000,000.00", AmountFormatter.format(new BigDecimal("1000000")));
        assertEquals("GHS 12.00", AmountFormatter.format(new BigDecimal("12")));
    }

    @Test
    void parse_acceptsPlainAndFormattedInput() {
        assertEquals(Optional.of(new BigDecimal("1389.00")), AmountFormatter.parse("1389.00"));
        assertEquals(Optional.of(new BigDecimal("1389")), AmountFormatter.parse("1389"));
        assertEquals(Optional.of(new BigDecimal("1389.00")), AmountFormatter.parse("1,389.00"));
        assertEquals(Optional.of(new BigDecimal("1234.56")), AmountFormatter.parse("12,34.56"));
    }

    @Test
    void parse_rejectsInvalidInput() {
        assertTrue(AmountFormatter.parse(null).isEmpty());
        assertTrue(AmountFormatter.parse("").isEmpty());
        assertTrue(AmountFormatter.parse("   ").isEmpty());
        assertTrue(AmountFormatter.parse("abc").isEmpty());
        assertTrue(AmountFormatter.parse("-5").isEmpty());
        assertTrue(AmountFormatter.parse("0").isEmpty());
        assertTrue(AmountFormatter.parse("0.00").isEmpty());
        assertTrue(AmountFormatter.parse("1.234").isEmpty());
        assertTrue(AmountFormatter.parse("12.34.56").isEmpty());
    }

    @Test
    void parseThenFormat_roundTripsThroughUserInput() {
        BigDecimal value = AmountFormatter.parse("1,389.00").orElseThrow();
        assertEquals("GHS 1,389.00", AmountFormatter.format(value));
    }
}
