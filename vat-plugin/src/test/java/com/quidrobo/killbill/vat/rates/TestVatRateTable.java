/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.rates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TestVatRateTable {

    private static final String PREFIX = "org.killbill.billing.plugin.vat.";

    @Test(groups = "fast")
    public void testDatedRatesAreSelectedByTaxPoint() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "rates.GB.standard.0.rate", "0.175");
        p.setProperty(PREFIX + "rates.GB.standard.0.from", "2008-12-01");
        p.setProperty(PREFIX + "rates.GB.standard.0.to", "2010-01-01");
        p.setProperty(PREFIX + "rates.GB.standard.1.rate", "0.20");
        p.setProperty(PREFIX + "rates.GB.standard.1.from", "2011-01-04");

        final VatRateTable table = VatRateTable.fromProperties(p, PREFIX);

        assertRate(table.findRate("GB", "standard", LocalDate.of(2009, 6, 1)), "0.175");
        assertRate(table.findRate("GB", "standard", LocalDate.of(2026, 9, 6)), "0.20");
        assertNull(table.findRate("GB", "standard", LocalDate.of(2010, 6, 1)),
                   "the 2010 gap between the two periods must not resolve to either of them");
    }

    @Test(groups = "fast")
    public void testUndatedAndPercentShorthand() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "rates.DE.standard", "19%");
        p.setProperty(PREFIX + "rates.IE.reduced", "0.09");

        final VatRateTable table = VatRateTable.fromProperties(p, PREFIX);

        assertRate(table.findRate("DE", "standard", LocalDate.of(2026, 9, 6)), "0.19");
        assertEquals(table.findRate("DE", "standard", LocalDate.of(2026, 9, 6)).toPercentLabel(), "19%");
        assertEquals(table.findRate("IE", "reduced", LocalDate.of(2026, 9, 6)).toPercentLabel(), "9%");
        assertNull(table.findRate("US", "standard", LocalDate.of(2026, 9, 6)));
        assertNull(table.findRate("DE", "reduced", LocalDate.of(2026, 9, 6)),
                   "the kind is part of the key: a standard rate is not a reduced rate");
        assertTrue(table.hasJurisdiction("gb") == table.hasJurisdiction("GB"),
                   "jurisdiction lookup is case insensitive");
    }

    /**
     * A dated entry beats an undated one for the same jurisdiction and kind.
     *
     * Without this, whichever of the two the map happened to visit first won, so a rate change
     * added alongside an existing plain entry applied or did not apply depending on hash order.
     */
    @Test(groups = "fast")
    public void testDatedRateBeatsUndatedRate() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "rates.GB.standard", "0.20");
        p.setProperty(PREFIX + "rates.GB.standard.0.rate", "0.125");
        p.setProperty(PREFIX + "rates.GB.standard.0.from", "2026-01-01");

        final VatRateTable table = VatRateTable.fromProperties(p, PREFIX);

        assertRate(table.findRate("GB", "standard", LocalDate.of(2026, 9, 6)), "0.125");
        assertRate(table.findRate("GB", "standard", LocalDate.of(2025, 9, 6)), "0.20");
    }

    @Test(groups = "fast")
    public void testLaterValidFromWinsWhenTwoPeriodsOverlap() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "rates.GB.standard.0.rate", "0.20");
        p.setProperty(PREFIX + "rates.GB.standard.0.from", "2011-01-04");
        p.setProperty(PREFIX + "rates.GB.standard.1.rate", "0.15");
        p.setProperty(PREFIX + "rates.GB.standard.1.from", "2026-01-01");

        final VatRateTable table = VatRateTable.fromProperties(p, PREFIX);

        assertRate(table.findRate("GB", "standard", LocalDate.of(2026, 9, 6)), "0.15");
    }

    /**
     * Two open-ended periods for the same rate are a configuration mistake, not a feature: whether
     * the right one applies is then a coin toss. The table has to name them.
     */
    @Test(groups = "fast")
    public void testOverlappingPeriodsAreReported() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "rates.GB.standard.0.rate", "0.20");
        p.setProperty(PREFIX + "rates.GB.standard.0.from", "2011-01-04");
        p.setProperty(PREFIX + "rates.GB.standard.1.rate", "0.15");
        p.setProperty(PREFIX + "rates.GB.standard.1.from", "2026-01-01");

        final List<String> overlaps = VatRateTable.fromProperties(p, PREFIX).findOverlaps();

        assertFalse(overlaps.isEmpty(), "an overlap must be reported");
        assertTrue(String.join(" | ", overlaps).contains("GB"), "the report has to name the jurisdiction");
    }

    @Test(groups = "fast")
    public void testNonOverlappingPeriodsAreNotReported() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "rates.GB.standard.0.rate", "0.175");
        p.setProperty(PREFIX + "rates.GB.standard.0.from", "2008-12-01");
        p.setProperty(PREFIX + "rates.GB.standard.0.to", "2011-01-04");
        p.setProperty(PREFIX + "rates.GB.standard.1.rate", "0.20");
        p.setProperty(PREFIX + "rates.GB.standard.1.from", "2011-01-04");

        assertTrue(VatRateTable.fromProperties(p, PREFIX).findOverlaps().isEmpty());
    }

    /**
     * "20" could be 20% or 2000%. Guessing at a tax rate is not acceptable, so it is refused and
     * the configuration report says so.
     */
    @Test(groups = "fast")
    public void testAmbiguousBareNumberIsRejected() {
        assertNull(VatRateTable.parseRate("20"));
        assertEquals(VatRateTable.parseRate("20%").compareTo(new BigDecimal("0.20")), 0);
        assertEquals(VatRateTable.parseRate("0.2").compareTo(new BigDecimal("0.2")), 0);
        assertNull(VatRateTable.parseRate("abc"));
        assertNull(VatRateTable.parseRate(null));
    }

    /** A rate is a fraction in [0, 1). 1.20 is somebody who meant 20% and wrote the multiplier. */
    @Test(groups = "fast")
    public void testOutOfRangeRatesAreRejected() {
        assertNull(VatRateTable.parseRate("1.20"), "a multiplier is not a rate");
        assertNull(VatRateTable.parseRate("-0.20"), "a negative rate is not a rate");
        assertNull(VatRateTable.parseRate("100%"), "100% is out of range and almost certainly a typo");
        assertNotNull(VatRateTable.parseRate("0"), "zero is a legitimate rate");
        assertNotNull(VatRateTable.parseRate("0.27"), "Hungary's standard rate is the real world maximum");
    }

    @Test(groups = "fast")
    public void testRecognisedRateKeys() {
        assertTrue(VatRateTable.isRecognisedRateKey("rates.GB.standard"));
        assertTrue(VatRateTable.isRecognisedRateKey("rates.GB.standard.0.rate"));
        assertTrue(VatRateTable.isRecognisedRateKey("rates.GB.standard.0.from"));
        assertTrue(VatRateTable.isRecognisedRateKey("rates.GB.standard.0.to"));
        assertFalse(VatRateTable.isRecognisedRateKey("rates.GB.standard.0.form"),
                    "a typo in the date key must not be silently ignored");
        assertFalse(VatRateTable.isRecognisedRateKey("rates.GBR.standard"),
                    "a three-letter country code is not a jurisdiction the parser reads");
        assertFalse(VatRateTable.isRecognisedRateKey("rates.GB"));
    }

    private static void assertRate(final VatRate rate, final String expected) {
        assertNotNull(rate, "expected a rate of " + expected + " but none was found");
        assertEquals(rate.getRate().compareTo(new BigDecimal(expected)), 0,
                     "expected " + expected + " but was " + rate.getRate().toPlainString());
    }
}
