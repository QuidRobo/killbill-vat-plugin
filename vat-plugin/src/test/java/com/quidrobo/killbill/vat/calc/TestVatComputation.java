/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.testng.annotations.Test;

import com.quidrobo.killbill.vat.core.PriceMode;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * The arithmetic, which is where a tax plugin is most easily and most expensively wrong.
 *
 * The invariant every test here defends: under VAT-inclusive pricing the customer is charged
 * exactly the catalogue price, to the penny, whatever the rate.
 */
public class TestVatComputation {

    @Test(groups = "fast")
    public void testExclusivePricingAddsVatOnTop() {
        final VatComputation.VatSplit split = split("120.00", "0.20", PriceMode.EXCLUSIVE);

        assertAmount(split.getNet(), "120.00");
        assertAmount(split.getVat(), "24.00");
        assertAmount(split.getGross(), "144.00");
        assertFalse(split.requiresRewrite(new BigDecimal("120.00")),
                    "an exclusive price is already net, so the charge line must be left alone");
    }

    @Test(groups = "fast")
    public void testInclusivePricingSplitsOutOfTheCataloguePrice() {
        final VatComputation.VatSplit split = split("120.00", "0.20", PriceMode.INCLUSIVE);

        assertAmount(split.getNet(), "100.00");
        assertAmount(split.getVat(), "20.00");
        assertAmount(split.getGross(), "120.00");
        assertTrue(split.requiresRewrite(new BigDecimal("120.00")),
                   "the charge line has to be rewritten down to net");
    }

    /**
     * 9.99 inclusive of 20% is 8.325 net. Computing VAT independently as gross * rate / (1 + rate)
     * gives 1.665, which rounds to 1.67; 8.33 + 1.67 is 10.00, a penny more than the price the
     * customer was quoted. Deriving VAT as gross minus net cannot drift.
     */
    @Test(groups = "fast")
    public void testInclusivePricingNeverDriftsFromTheQuotedPrice() {
        final VatComputation.VatSplit split = split("9.99", "0.20", PriceMode.INCLUSIVE);

        assertAmount(split.getNet(), "8.33");
        assertAmount(split.getVat(), "1.66");
        assertAmount(split.getGross(), "9.99");
        assertEquals(split.getNet().add(split.getVat()).compareTo(new BigDecimal("9.99")), 0,
                     "net plus VAT must equal the catalogue price exactly");
    }

    @Test(groups = "fast")
    public void testInclusivePricingAtEveryRateReturnsTheCataloguePrice() {
        final String[] rates = {"0.05", "0.09", "0.17", "0.19", "0.20", "0.21", "0.22", "0.23", "0.27"};
        final String[] amounts = {"0.01", "0.99", "9.99", "12.34", "19.99", "100.00", "1234.56"};

        for (final String rate : rates) {
            for (final String amount : amounts) {
                final VatComputation.VatSplit split = split(amount, rate, PriceMode.INCLUSIVE);
                assertEquals(split.getNet().add(split.getVat()).compareTo(new BigDecimal(amount)), 0,
                             "net + VAT != " + amount + " at " + rate);
            }
        }
    }

    @Test(groups = "fast")
    public void testExclusiveVatRoundsHalfUp() {
        assertAmount(split("33.33", "0.20", PriceMode.EXCLUSIVE).getVat(), "6.67");
    }

    @Test(groups = "fast")
    public void testZeroRateLeavesTheChargeAlone() {
        final VatComputation.VatSplit split = split("120.00", "0", PriceMode.INCLUSIVE);

        assertAmount(split.getNet(), "120.00");
        assertAmount(split.getVat(), "0.00");
        assertFalse(split.requiresRewrite(new BigDecimal("120.00")),
                    "at 0% there is nothing to split out, so nothing to rewrite");
    }

    @Test(groups = "fast")
    public void testNineteenPercentInclusive() {
        final VatComputation.VatSplit split = split("120.00", "0.19", PriceMode.INCLUSIVE);

        assertAmount(split.getNet(), "100.84");
        assertAmount(split.getVat(), "19.16");
        assertAmount(split.getGross(), "120.00");
    }

    @Test(groups = "fast")
    public void testNegativeAmountsKeepTheirSign() {
        // Credits and adjustments arrive as negative amounts and must be taxed, not ignored.
        final VatComputation.VatSplit split = split("-100.00", "0.20", PriceMode.EXCLUSIVE);

        assertAmount(split.getNet(), "-100.00");
        assertAmount(split.getVat(), "-20.00");

        final VatComputation.VatSplit inclusive = split("-120.00", "0.20", PriceMode.INCLUSIVE);
        assertAmount(inclusive.getNet(), "-100.00");
        assertAmount(inclusive.getVat(), "-20.00");
    }

    @Test(groups = "fast")
    public void testNullAmountIsSafe() {
        assertEquals(split(null, "0.20", PriceMode.INCLUSIVE).getVat().compareTo(BigDecimal.ZERO), 0);
    }

    @Test(groups = "fast")
    public void testPriceModeParsing() {
        assertEquals(PriceMode.parse("inclusive", PriceMode.EXCLUSIVE), PriceMode.INCLUSIVE);
        assertEquals(PriceMode.parse("GROSS", PriceMode.EXCLUSIVE), PriceMode.INCLUSIVE);
        assertEquals(PriceMode.parse("banana", PriceMode.EXCLUSIVE), PriceMode.EXCLUSIVE,
                     "an unreadable value falls back rather than throwing during invoicing");
        assertEquals(PriceMode.parse(null, PriceMode.INCLUSIVE), PriceMode.INCLUSIVE);
    }

    // ------------------------------------------------------------------
    // Which price mode actually applies
    // ------------------------------------------------------------------

    @Test(groups = "fast")
    public void testUnadjustedItemsKeepTheirConfiguredPriceMode() {
        assertEquals(VatComputation.effectivePriceMode(PriceMode.INCLUSIVE, false), PriceMode.INCLUSIVE);
        assertEquals(VatComputation.effectivePriceMode(PriceMode.EXCLUSIVE, false), PriceMode.EXCLUSIVE);
    }

    /**
     * An adjusted item cannot be rewritten: the adjustment was computed against the gross amount,
     * so reducing the charge under it changes what the customer was credited.
     */
    @Test(groups = "fast")
    public void testAnAdjustedInclusiveItemFallsBackToChargingOnTop() {
        assertEquals(VatComputation.effectivePriceMode(PriceMode.INCLUSIVE, true), PriceMode.EXCLUSIVE);
    }

    @Test(groups = "fast")
    public void testAdjustmentsDoNotDisturbExclusivePricing() {
        assertEquals(VatComputation.effectivePriceMode(PriceMode.EXCLUSIVE, true), PriceMode.EXCLUSIVE);
    }

    /**
     * The bug that fallback fixes, in figures.
     *
     * Under the old behaviour an adjusted inclusive item kept its gross amount on the invoice
     * while the TAX item carried the VAT extracted from that gross: 100.00 gross at 20% produced
     * a 16.67 tax item sitting beside a 100.00 charge. That is 16.67% of the line, a rate that
     * exists nowhere, on a total nobody quoted.
     */
    @Test(groups = "fast")
    public void testTheAdjustedItemFallbackProducesACoherentLine() {
        final PriceMode mode = VatComputation.effectivePriceMode(PriceMode.INCLUSIVE, true);
        final VatComputation.VatSplit split = VatComputation.split(new BigDecimal("100.00"),
                                                                   new BigDecimal("0.20"), mode,
                                                                   2, RoundingMode.HALF_UP);

        assertAmount(split.getNet(), "100.00");
        assertAmount(split.getVat(), "20.00");
        assertFalse(split.requiresRewrite(new BigDecimal("100.00")), "so nothing is rewritten");
    }

    private static VatComputation.VatSplit split(final String amount,
                                                 final String rate,
                                                 final PriceMode mode) {
        return VatComputation.split(amount == null ? null : new BigDecimal(amount),
                                    new BigDecimal(rate), mode, 2, RoundingMode.HALF_UP);
    }

    private static void assertAmount(final BigDecimal actual, final String expected) {
        assertEquals(actual.compareTo(new BigDecimal(expected)), 0,
                     "expected " + expected + " but was " + actual.toPlainString());
    }
}
