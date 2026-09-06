/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.rates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * One VAT rate in one jurisdiction, valid over a half-open date range.
 *
 * Rate history is modelled as several {@code VatRate} rows rather than one mutable rate, because
 * a credit note against a supply made under an older rate must reverse VAT at the rate that
 * applied then, not at today's rate.
 *
 * Uses {@code java.time.LocalDate} rather than Joda so the rate engine stays free of Kill Bill
 * dependencies and can be tested on its own. Conversion happens at the calculator boundary.
 */
public final class VatRate {

    private final String jurisdiction;
    private final String kind;
    private final BigDecimal rate;
    private final LocalDate validFrom;
    private final LocalDate validTo;

    /**
     * @param jurisdiction ISO 3166-1 alpha-2, upper case
     * @param kind         "standard", "reduced", "zero", or any label you configure
     * @param rate         fraction, so 0.20 for 20%
     * @param validFrom    inclusive, null means "since forever"
     * @param validTo      exclusive, null means "still current"
     */
    public VatRate(final String jurisdiction,
                   final String kind,
                   final BigDecimal rate,
                   final LocalDate validFrom,
                   final LocalDate validTo) {
        this.jurisdiction = jurisdiction == null ? null : jurisdiction.trim().toUpperCase();
        this.kind = kind == null ? "standard" : kind.trim().toLowerCase();
        this.rate = rate;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    public String getJurisdiction() {
        return jurisdiction;
    }

    public String getKind() {
        return kind;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    /** Half-open: {@code validFrom} is included, {@code validTo} is not. */
    public boolean appliesOn(final LocalDate date) {
        if (date == null) {
            return validTo == null;
        }
        if (validFrom != null && date.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || date.isBefore(validTo);
    }

    /** "20%", "19%", "5.5%". Used in the tax item description, which is what shows on the invoice. */
    public String toPercentLabel() {
        if (rate == null) {
            return "0%";
        }
        return rate.multiply(BigDecimal.valueOf(100))
                   .setScale(4, RoundingMode.HALF_UP)
                   .stripTrailingZeros()
                   .toPlainString() + "%";
    }

    @Override
    public String toString() {
        return jurisdiction + "/" + kind + " " + toPercentLabel()
               + " [" + (validFrom == null ? "-" : validFrom) + ", " + (validTo == null ? "-" : validTo) + ")";
    }
}
