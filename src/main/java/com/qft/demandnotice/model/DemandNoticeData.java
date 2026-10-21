package com.qft.demandnotice.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Immutable value object holding every variable field of a demand notice.
 *
 * @param letterDate        date printed at the top of the letter
 * @param contributionMonth the month (and year) the demand relates to
 * @param amount            contribution amount owed, in Ghana Cedis
 * @param deadline          payment deadline printed in the body of the letter
 */
public record DemandNoticeData(
        LocalDate letterDate,
        YearMonth contributionMonth,
        BigDecimal amount,
        LocalDate deadline) {

    public DemandNoticeData {
        Objects.requireNonNull(letterDate, "letterDate must not be null");
        Objects.requireNonNull(contributionMonth, "contributionMonth must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("amount must have at most 2 decimal places");
        }
    }
}
