/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.core;

import java.util.Properties;

import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Which diagnostics survive VAT being switched off.
 *
 * The dividing line is one question: does the message claim something about the VAT that will or
 * will not be charged? If it does, it is untrue of a tenant with VAT off and must not be emitted.
 * If it merely describes the text that was uploaded, it holds either way and is worth keeping,
 * because it is exactly what would bite on the day VAT is switched on.
 */
public class TestVatConfigOptional {

    private static VatConfig config(final String... keysAndValues) {
        final Properties p = new Properties();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            p.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new VatConfig(p);
    }

    private static String joined(final java.util.List<String> problems) {
        return String.join(" | ", problems);
    }

    /** enabled defaults to true, so nothing about existing deployments changes. */
    @Test(groups = "fast")
    public void testEnabledByDefault() {
        assertTrue(config().isEnabled(), "an existing tenant must not have VAT switched off under it");
    }

    @Test(groups = "fast")
    public void testExplicitFalseDisables() {
        assertFalse(config(VatConfig.ENABLED, "false").isEnabled());
    }

    /**
     * The message is not merely unhelpful when VAT is off, it is false: no VAT is being charged
     * because it was switched off, not because the rate table is empty.
     */
    @Test(groups = "fast")
    public void testDisabledSuppressesTheNoRatesWarning() {
        final VatConfig disabled = config(VatConfig.ENABLED, "false");

        assertTrue(disabled.getRateTable().all().isEmpty(), "precondition");
        assertFalse(joined(disabled.getProblems()).contains("No VAT rates are configured"),
                    joined(disabled.getProblems()));
        assertTrue(joined(disabled.getTaxationProblems()).contains("No VAT rates are configured"),
                   "the diagnostic still exists, it is just not applicable");
    }

    /** "the reverse charge will be applied on it" is untrue when nothing is being charged. */
    @Test(groups = "fast")
    public void testDisabledSuppressesTheValidationWarning() {
        final VatConfig disabled = config(VatConfig.ENABLED, "false",
                                          VatConfig.VAT_NUMBER_VALIDATION, "NONE",
                                          "org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");

        assertFalse(joined(disabled.getProblems()).contains("vatNumberValidation is NONE"),
                    joined(disabled.getProblems()));
    }

    /** With VAT on, every one of those is reported exactly as before. */
    @Test(groups = "fast")
    public void testEnabledReportsEverything() {
        final VatConfig enabled = config(VatConfig.VAT_NUMBER_VALIDATION, "NONE");

        assertTrue(joined(enabled.getProblems()).contains("No VAT rates are configured"));
        assertTrue(joined(enabled.getProblems()).contains("vatNumberValidation is NONE"));
    }

    /** A key nothing reads is a key nothing reads. Switching VAT off does not make it correct. */
    @Test(groups = "fast")
    public void testDisabledStillReportsUnknownKeys() {
        final VatConfig disabled = config(VatConfig.ENABLED, "false",
                                          "org.killbill.billing.plugin.vat.suplierCountry", "GB");

        assertTrue(joined(disabled.getProblems()).contains("suplierCountry"),
                   joined(disabled.getProblems()));
        assertTrue(joined(disabled.getConfigurationProblems()).contains("suplierCountry"));
    }

    /** Likewise a rate that will not parse: it is unreadable text, not a taxation consequence. */
    @Test(groups = "fast")
    public void testDisabledStillReportsUnparseableRates() {
        final VatConfig disabled = config(VatConfig.ENABLED, "false",
                                          "org.killbill.billing.plugin.vat.rates.GB.standard", "twenty");

        assertTrue(joined(disabled.getProblems()).contains("could not be read"),
                   joined(disabled.getProblems()));
    }

    /** The two lists partition the diagnostics: getProblems with VAT on is exactly their union. */
    @Test(groups = "fast")
    public void testProblemsAreThePartitionOfBothLists() {
        final VatConfig enabled = config(VatConfig.VAT_NUMBER_VALIDATION, "NONE",
                                         "org.killbill.billing.plugin.vat.suplierCountry", "GB");

        assertTrue(enabled.getProblems().containsAll(enabled.getConfigurationProblems()));
        assertTrue(enabled.getProblems().containsAll(enabled.getTaxationProblems()));
        assertTrue(enabled.getProblems().size()
                   == enabled.getConfigurationProblems().size() + enabled.getTaxationProblems().size(),
                   "a diagnostic must be in exactly one list, not both and not neither");
    }

    /**
     * The zero-VAT record is on by default: it is what carries the treatment to the invoice
     * formatter, which on Kill Bill 0.24.x has no tenant context and can read nothing itself.
     */
    @Test(groups = "fast")
    public void testZeroVatItemsAreRecordedByDefault() {
        assertTrue(config().isRecordZeroVatItems());
    }

    /** The kill switch, for the one part of this that cannot be proven outside a running Kill Bill. */
    @Test(groups = "fast")
    public void testZeroVatRecordingCanBeSwitchedOff() {
        assertFalse(config(VatConfig.RECORD_ZERO_VAT_ITEMS, "false").isRecordZeroVatItems());
        assertTrue(config(VatConfig.RECORD_ZERO_VAT_ITEMS, "true").isRecordZeroVatItems());
    }

    /** It is a recognised key, so setting it does not read as a typo. */
    @Test(groups = "fast")
    public void testZeroVatRecordingIsNotReportedAsAnUnknownKey() {
        assertFalse(joined(config(VatConfig.RECORD_ZERO_VAT_ITEMS, "false").getProblems())
                            .contains("recordZeroVatItems"),
                    joined(config(VatConfig.RECORD_ZERO_VAT_ITEMS, "false").getProblems()));
    }

    /** A tenant with VAT off and a clean configuration has nothing at all to say. */
    @Test(groups = "fast")
    public void testDisabledAndCleanIsSilent() {
        assertTrue(config(VatConfig.ENABLED, "false").getProblems().isEmpty(),
                   joined(config(VatConfig.ENABLED, "false").getProblems()));
    }
}
