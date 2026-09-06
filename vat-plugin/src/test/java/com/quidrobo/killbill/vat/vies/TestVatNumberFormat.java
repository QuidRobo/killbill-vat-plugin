/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.vies;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Offline VAT number checks.
 *
 * These prove a number is well formed. They do not prove a registration exists, which is why
 * {@code vatNumberValidation = EXTERNAL} is the production setting and this is only a first gate.
 */
public class TestVatNumberFormat {

    @Test(groups = "fast")
    public void testNormalisation() {
        assertEquals(VatNumberFormat.normalise("gb 484-549.056"), "GB484549056");
        assertNull(VatNumberFormat.normalise("  "), "whitespace only is nothing at all");
        assertNull(VatNumberFormat.normalise(null));
        assertEquals(VatNumberFormat.countryOf(VatNumberFormat.normalise("DE 136 695 976")), "DE");
        assertNull(VatNumberFormat.countryOf("ZZ123456789"), "an unknown prefix is not a country");
        assertNull(VatNumberFormat.countryOf("GB"));
    }

    @Test(groups = "fast")
    public void testCheckDigitsAcrossTheImplementedCountries() {
        assertTrue(VatNumberFormat.isPlausible("GB 484549056"));
        assertTrue(VatNumberFormat.isPlausible("GB-484-549-056"), "separators are stripped first");
        assertTrue(VatNumberFormat.isPlausible("DE136695976"));
        assertTrue(VatNumberFormat.isPlausible("IT00743110157"));
        assertTrue(VatNumberFormat.isPlausible("NL004495445B01"));
        assertTrue(VatNumberFormat.isPlausible("FR96552100554"));
        assertTrue(VatNumberFormat.isPlausible("XI484549056"), "Northern Ireland uses the GB rules");
        assertTrue(VatNumberFormat.isPlausible("GBGD001"), "government departments carry no check digits");
    }

    @Test(groups = "fast")
    public void testCorruptedNumbersFail() {
        assertFalse(VatNumberFormat.isPlausible("GB484549057"), "one digit out must not pass");
        assertFalse(VatNumberFormat.isPlausible("DE136695975"));
        assertFalse(VatNumberFormat.isPlausible("GB48454905"), "too short");
        assertFalse(VatNumberFormat.isPlausible("NL123456789"), "structurally wrong: no B and no suffix");
        assertFalse(VatNumberFormat.isPlausible("ZZ123456789"), "an unknown prefix is refused, not guessed");
        assertFalse(VatNumberFormat.isPlausible(null));
        assertFalse(VatNumberFormat.isPlausible("   "));
    }

    /**
     * GB000000000, IT00000000000 and NL000000000B01 all satisfy their national check digits.
     * Arithmetic cannot tell a real number from a placeholder somebody typed to get past a form,
     * so an all-zero body is refused outright.
     */
    @Test(groups = "fast")
    public void testAllZeroNumbersAreRefused() {
        assertFalse(VatNumberFormat.isPlausible("GB000000000"));
        assertFalse(VatNumberFormat.isPlausible("IT00000000000"));
        assertFalse(VatNumberFormat.isPlausible("NL000000000B00"));
        assertFalse(VatNumberFormat.isPlausible("DE000000000"));
    }

    /**
     * A country with no implemented checksum is accepted on structure alone. Rejecting it would
     * mean refusing a genuine Polish business because this library does not know Poland's
     * algorithm, and VIES is the authority in either case.
     */
    @Test(groups = "fast")
    public void testUnimplementedCountriesFallBackToStructure() {
        assertTrue(VatNumberFormat.isPlausible("PL1234567890"));
        assertFalse(VatNumberFormat.isPlausible("PL12345"), "structure is still enforced");
    }
}
