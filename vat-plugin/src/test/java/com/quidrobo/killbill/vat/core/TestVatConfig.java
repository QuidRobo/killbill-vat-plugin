/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.core;

import java.math.RoundingMode;
import java.util.Properties;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * The configuration diagnostics.
 *
 * The premise of every test here: a tax plugin whose configuration fails silently is worse than
 * one that refuses to start, because the failure surfaces as money at quarter end rather than as
 * an error at deploy time. Kill Bill treats a misspelt property key as a key nobody reads, so
 * every one of these mistakes is invisible unless the plugin says something.
 */
public class TestVatConfig {

    private static final String PREFIX = "org.killbill.billing.plugin.vat.";

    @Test(groups = "fast")
    public void testASoundConfigurationReportsNothing() {
        final Properties p = sound();

        assertTrue(new VatConfig(p).getProblems().isEmpty(),
                   "a clean UK-only configuration must produce no noise, or the report gets ignored");
    }

    @Test(groups = "fast")
    public void testAnUnrecognisedPropertyIsReported() {
        final Properties p = sound();
        p.setProperty(PREFIX + "roundng.scale", "2");

        assertTrue(problems(p).contains("roundng.scale"));
    }

    /**
     * rates.GB.standrd registers the GB jurisdiction while leaving domestic supplies with no
     * standard rate, so every UK invoice silently comes out at 0%.
     */
    @Test(groups = "fast")
    public void testAMisspeltRateKindIsReported() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "supplierCountry", "GB");
        p.setProperty(PREFIX + "rates.GB.standrd", "0.20");

        assertTrue(problems(p).contains("No standard rate is in force today for the supplier country GB"));
    }

    @Test(groups = "fast")
    public void testAnUnusableRateKeyIsReported() {
        final Properties p = sound();
        p.setProperty(PREFIX + "rates.GBR.standard", "0.20");
        p.setProperty(PREFIX + "rates.DE.standard.0.rat", "0.19");

        final String reported = problems(p);
        assertTrue(reported.contains("rates.GBR.standard"), "a three-letter country code is not a rate key");
        assertTrue(reported.contains("rates.DE.standard.0.rat"), "a typo in the field name is not a rate key");
    }

    @Test(groups = "fast")
    public void testNoRatesAtAllIsReported() {
        assertTrue(problems(new Properties()).contains("No VAT rates are configured"));
    }

    @Test(groups = "fast")
    public void testAnUnreadableRateIsReported() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "rates.GB.standard", "20");

        assertTrue(problems(p).contains("could not be read"),
                   "a bare 20 is ambiguous between 20% and 2000% and must not be guessed at");
    }

    @Test(groups = "fast")
    public void testOverlappingRatePeriodsAreReported() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "supplierCountry", "GB");
        p.setProperty(PREFIX + "rates.GB.standard.0.rate", "0.20");
        p.setProperty(PREFIX + "rates.GB.standard.0.from", "2011-01-04");
        p.setProperty(PREFIX + "rates.GB.standard.1.rate", "0.15");
        p.setProperty(PREFIX + "rates.GB.standard.1.from", "2026-01-01");

        assertTrue(problems(p).contains("overlap"));
    }

    @Test(groups = "fast")
    public void testEuRatesWithoutAnOssRegistrationAreReported() {
        final Properties p = sound();
        p.setProperty(PREFIX + "rates.DE.standard", "0.19");

        assertTrue(problems(p).contains("ossRegistered is false"),
                   "configured EU rates that will never be charged are a trap worth naming");
    }

    @Test(groups = "fast")
    public void testWeakVatNumberValidationIsFlagged() {
        final Properties checksum = new Properties();
        checksum.setProperty(PREFIX + "supplierCountry", "GB");
        checksum.setProperty(PREFIX + "rates.GB.standard", "0.20");
        assertTrue(problems(checksum).contains("not that the registration exists"),
                   "CHECKSUM is the default, so the report has to say what it does not prove");

        final Properties none = sound();
        none.setProperty(PREFIX + "vatNumberValidation", "NONE");
        assertTrue(problems(none).contains("trusted without any check"));
    }

    /**
     * A rounding mode that is not symmetric about zero charges a different amount of VAT on a
     * credit than on the sale it reverses, so the two never net to nothing.
     */
    @Test(groups = "fast")
    public void testAsymmetricRoundingIsRejected() {
        final Properties p = sound();
        p.setProperty(PREFIX + "rounding.mode", "FLOOR");
        final VatConfig config = new VatConfig(p);

        assertTrue(String.join(" | ", config.getProblems()).contains("not symmetric about zero"));
        assertEquals(config.getRoundingMode(), RoundingMode.HALF_UP,
                     "and it must fall back rather than use the unsafe mode it just objected to");
    }

    @Test(groups = "fast")
    public void testSymmetricRoundingModesAreAccepted() {
        for (final String mode : new String[] {"HALF_UP", "HALF_DOWN", "HALF_EVEN"}) {
            final Properties p = sound();
            p.setProperty(PREFIX + "rounding.mode", mode);
            final VatConfig config = new VatConfig(p);

            assertEquals(config.getRoundingMode(), RoundingMode.valueOf(mode));
            assertTrue(config.getProblems().isEmpty(), mode + " must be accepted without comment");
        }
    }

    @Test(groups = "fast")
    public void testAnUnknownRoundingModeIsReported() {
        final Properties p = sound();
        p.setProperty(PREFIX + "rounding.mode", "BANANA");

        assertTrue(problems(p).contains("is not a rounding mode"));
        assertEquals(new VatConfig(p).getRoundingMode(), RoundingMode.HALF_UP);
    }

    /**
     * INCLUSIV silently reverted a plan to EXCLUSIVE, which then charged 20% on top of a price
     * that already contained it. Every customer on that plan was overcharged by a fifth.
     */
    @Test(groups = "fast")
    public void testAMisspeltPriceModeOverrideIsReported() {
        final Properties p = sound();
        p.setProperty(PREFIX + "priceMode.plans.growth-monthly", "INCLUSIV");

        assertTrue(problems(p).contains("is not INCLUSIVE or EXCLUSIVE"));
    }

    @Test(groups = "fast")
    public void testValidPlanAndProductOverridesAreNotFlaggedAsTypos() {
        final Properties p = sound();
        p.setProperty(PREFIX + "priceMode.plans.growth-monthly", "INCLUSIVE");
        p.setProperty(PREFIX + "priceMode.products.Growth", "EXCLUSIVE");
        final VatConfig config = new VatConfig(p);

        assertTrue(config.getProblems().isEmpty());
        assertEquals(config.getPriceMode("growth-monthly", "Growth"), PriceMode.INCLUSIVE,
                     "the plan override is the more specific of the two and has to win");
        assertEquals(config.getPriceMode("other-monthly", "Growth"), PriceMode.EXCLUSIVE);
        assertEquals(config.getPriceMode("other-monthly", "Other"), config.getDefaultPriceMode());
    }

    @Test(groups = "fast")
    public void testTenantDefaultPriceModeApplies() {
        final Properties p = sound();
        p.setProperty(PREFIX + "priceMode", "INCLUSIVE");
        final VatConfig config = new VatConfig(p);

        assertEquals(config.getDefaultPriceMode(), PriceMode.INCLUSIVE);
        assertEquals(config.getPriceMode(null, null), PriceMode.INCLUSIVE);
    }

    private static Properties sound() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "supplierCountry", "GB");
        p.setProperty(PREFIX + "rates.GB.standard", "0.20");
        p.setProperty(PREFIX + "vatNumberValidation", "EXTERNAL");
        return p;
    }

    private static String problems(final Properties p) {
        return String.join(" | ", new VatConfig(p).getProblems());
    }
}
