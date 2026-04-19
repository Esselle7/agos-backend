package com.agostinelli.gestionale.shared.dto;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Filtro date con supporto a periodi predefiniti (MTD/QTD/YTD/CUSTOM).
 * Il metodo {@code resolveRange} espande il periodo in date concrete.
 */
public record DateRangeFilter(
        LocalDate from,
        LocalDate to,
        Period period
) {
    public enum Period {
        MTD, QTD, YTD, CUSTOM
    }

    public static DateRangeFilter resolveRange(Period period) {
        LocalDate today = LocalDate.now();
        return switch (period) {
            case MTD -> new DateRangeFilter(today.withDayOfMonth(1), today, period);
            case QTD -> new DateRangeFilter(startOfCurrentQuarter(today), today, period);
            case YTD -> new DateRangeFilter(today.withDayOfYear(1), today, period);
            case CUSTOM -> new DateRangeFilter(today, today, period);
        };
    }

    private static LocalDate startOfCurrentQuarter(LocalDate date) {
        int month = ((date.getMonthValue() - 1) / 3) * 3 + 1;
        return date.withMonth(month).withDayOfMonth(1);
    }
}
