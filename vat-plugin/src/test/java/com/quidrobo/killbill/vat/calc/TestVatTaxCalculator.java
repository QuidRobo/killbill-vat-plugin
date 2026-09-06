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

/**
 * The one calculator decision that does not need a Kill Bill invoice to exercise.
 *
 * The rest of {@code compute} is glue over {@code PluginTaxCalculator}, and testing it means
 * standing up an invoice, so it is covered by the integration checklist in the README rather than
 * here. This decision is not glue: getting it wrong charges the wrong amount of tax.
 */
public class TestVatTaxCalculator {

    @Test(groups = "fast")
    public void testUnadjustedItemsKeepTheirConfiguredPriceMode() {
        assertEquals(VatTaxCalculator.effectivePriceMode(PriceMode.INCLUSIVE, false), PriceMode.INCLUSIVE);
        assertEquals(VatTaxCalculator.effectivePriceMode(PriceMode.EXCLUSIVE, false), PriceMode.EXCLUSIVE);
    }

    /**
     * An adjusted item cannot be rewritten: the adjustment was computed against the gross amount,
     * so reducing the charge under it changes what the customer was credited.
     */
    @Test(groups = "fast")
    public void testAnAdjustedInclusiveItemFallsBackToChargingOnTop() {
        assertEquals(VatTaxCalculator.effectivePriceMode(PriceMode.INCLUSIVE, true), PriceMode.EXCLUSIVE);
    }

    @Test(groups = "fast")
    public void testAdjustmentsDoNotDisturbExclusivePricing() {
        assertEquals(VatTaxCalculator.effectivePriceMode(PriceMode.EXCLUSIVE, true), PriceMode.EXCLUSIVE);
    }

    /**
     * The bug this fallback fixes, spelled out in figures.
     *
     * Under the old behaviour an adjusted inclusive item kept its gross amount on the invoice
     * while the TAX item carried the VAT extracted from that gross. 100.00 gross at 20% produced
     * a 16.67 tax item sitting beside a 100.00 charge: 16.67% of the line, a rate that exists
     * nowhere, on a total nobody quoted.
     */
    @Test(groups = "fast")
    public void testTheFallbackProducesACoherentLine() {
        final PriceMode mode = VatTaxCalculator.effectivePriceMode(PriceMode.INCLUSIVE, true);
        final VatComputation.VatSplit split = VatComputation.split(new BigDecimal("100.00"),
                                                                   new BigDecimal("0.20"), mode,
                                                                   2, RoundingMode.HALF_UP);

        assertEquals(split.getNet().compareTo(new BigDecimal("100.00")), 0,
                     "the charge line is left exactly as the adjustment saw it");
        assertEquals(split.getVat().compareTo(new BigDecimal("20.00")), 0,
                     "and the VAT is a real 20% of it");
        assertEquals(split.requiresRewrite(new BigDecimal("100.00")), false,
                     "so nothing is rewritten");
    }
}
