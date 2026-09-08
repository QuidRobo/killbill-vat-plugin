/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.calc;

import java.math.BigDecimal;

import org.testng.annotations.Test;

import com.quidrobo.killbill.vat.resolve.VatTreatment;
import com.quidrobo.killbill.vat.resolve.VatTreatmentKind;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The writing half of the contract that makes the invoice formatter multi tenant.
 *
 * <p>The formatter on Kill Bill 0.24.x is handed no tenant context, so it cannot read the account
 * and cannot work out the VAT treatment for itself. The plugin can, because it runs with a real
 * tenant, so it writes the answer onto the tax item and the formatter reads it back off the
 * invoice. That removes the single-tenant JVM property the formatter used to depend on.
 *
 * <p>These tests pin the literal strings. The reader is a separate class in a separate bundle, by
 * design, so nothing but a test stops the two ends drifting apart, and a drift would show up as a
 * missing or wrong legal statement on an invoice rather than as a failure anyone would notice.
 */
public class TestVatItemDetails {

    private static VatTreatment domestic() {
        return VatTreatment.charging(VatTreatmentKind.DOMESTIC, "GB",
                                     new BigDecimal("0.20"), "VAT 20%", "domestic supply");
    }

    @Test(groups = "fast")
    public void testWritesEveryFieldTheFormatterNeeds() {
        final String json = VatItemDetails.write(domestic(), "GB", "GB", null);

        assertTrue(json.startsWith("{\"plugin\":\"killbill-vat\""), json);
        assertTrue(json.contains("\"treatment\":\"DOMESTIC\""), json);
        assertTrue(json.contains("\"jurisdiction\":\"GB\""), json);
        assertTrue(json.contains("\"supplierCountry\":\"GB\""), json);
        assertTrue(json.contains("\"customerCountry\":\"GB\""), json);
        assertTrue(json.endsWith("}"), json);
    }

    /**
     * The marker comes first and is what the reader checks. itemDetails is a shared field any
     * plugin may write, and reading somebody else's payload as a VAT decision would put a false
     * statement on an invoice.
     */
    @Test(groups = "fast")
    public void testMarkerIsAlwaysFirst() {
        assertTrue(VatItemDetails.write(domestic(), "GB", "DE", "DE123456789")
                                 .startsWith("{\"plugin\":\"killbill-vat\","));
    }

    /**
     * The exact payload, character for character.
     *
     * <p>This same literal is the {@code FULL} constant in the formatter bundle's own test. Nothing
     * else connects the two ends: they are separate bundles by design, one writing and one parsing
     * by hand. If either side changes shape, one of these two tests fails. Without them, the only
     * symptom would be a VAT invoice quietly losing its reverse charge legend, which is a legal
     * statement, on a document nobody re-reads.
     */
    @Test(groups = "fast")
    public void testExactPayloadTheFormatterExpects() {
        final VatTreatment reverse = VatTreatment.reverseCharge("DE", "DE123456789", "Reverse charge", "EU B2B");

        assertEquals(VatItemDetails.write(reverse, "GB", "DE", "DE123456789"),
                     "{\"plugin\":\"killbill-vat\",\"treatment\":\"REVERSE_CHARGE\","
                     + "\"jurisdiction\":\"DE\",\"rate\":\"0.0000\",\"supplierCountry\":\"GB\","
                     + "\"customerCountry\":\"DE\",\"customerVatNumber\":\"DE123456789\"}");
    }

    /** The VAT number is what a reverse charge invoice is legally required to show. */
    @Test(groups = "fast")
    public void testCarriesTheCustomerVatNumber() {
        final VatTreatment reverse = VatTreatment.reverseCharge("DE", "DE123456789", "Reverse charge", "EU B2B");
        final String json = VatItemDetails.write(reverse, "GB", "DE", "DE123456789");

        assertTrue(json.contains("\"treatment\":\"REVERSE_CHARGE\""), json);
        assertTrue(json.contains("\"customerVatNumber\":\"DE123456789\""), json);
    }

    /** Absent fields are omitted rather than written as empty strings or the word null. */
    @Test(groups = "fast")
    public void testOmitsAbsentFields() {
        final String json = VatItemDetails.write(domestic(), "GB", "GB", "   ");

        assertTrue(!json.contains("customerVatNumber"), json);
        assertTrue(!json.contains("null"), json);
    }

    /**
     * Plain decimal, never scientific notation. A rate written as 2E-1 is not something a hand
     * written reader on the other side is going to get right.
     */
    @Test(groups = "fast")
    public void testRateIsPlainAndFixedScale() {
        assertTrue(VatItemDetails.write(domestic(), "GB", "GB", null).contains("\"rate\":\"0.2000\""));

        final VatTreatment tiny = VatTreatment.charging(VatTreatmentKind.DOMESTIC, "GB",
                                                        new BigDecimal("0.00001"), "VAT", "tiny");
        // Checked on the rate field alone: the payload legitimately contains capital letters
        // elsewhere, DOMESTIC among them, so scanning the whole string proves nothing.
        final String json = VatItemDetails.write(tiny, "GB", "GB", null);
        final int at = json.indexOf("\"rate\":\"") + 8;
        final String written = json.substring(at, json.indexOf('"', at));
        assertTrue(!written.contains("E") && !written.contains("e"),
                   "no scientific notation in the rate: " + written);
    }

    /** A zero rate is a decision worth recording, not an absence. */
    @Test(groups = "fast")
    public void testZeroRateIsStillWritten() {
        final VatTreatment outside = VatTreatment.outsideScope("US", "Outside scope", "non-EU");
        final String json = VatItemDetails.write(outside, "GB", "US", null);

        assertTrue(json.contains("\"treatment\":\"OUTSIDE_SCOPE\""), json);
        assertTrue(json.contains("\"rate\":\"0.0000\""), json);
    }

    @Test(groups = "fast")
    public void testNullTreatmentWritesNothing() {
        assertNull(VatItemDetails.write(null, "GB", "GB", "GB123456789"));
    }

    /** A quote arriving in any field must not produce a payload the reader cannot parse. */
    @Test(groups = "fast")
    public void testEscapesQuotesAndBackslashes() {
        assertEquals(VatItemDetails.escape("a\"b"), "a\\\"b");
        assertEquals(VatItemDetails.escape("a\\b"), "a\\\\b");
        assertEquals(VatItemDetails.escape("a\nb"), "a\\nb");
        assertEquals(VatItemDetails.escape("plain"), "plain");
    }

    @Test(groups = "fast")
    public void testEscapesControlCharacters() {
        assertEquals(VatItemDetails.escape("ab"), "a\\u0001b");
    }
}
