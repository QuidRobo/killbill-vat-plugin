/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.formatter;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

/**
 * The reading half of the contract that makes this formatter multi tenant.
 *
 * <p>Kill Bill 0.24.x gives a formatter no tenant context, so it cannot read the account behind an
 * invoice. Instead the plugin records the VAT decision on the tax item while it still has a tenant,
 * and this reads it back. The strings below are the ones the plugin's own test pins, so the two
 * bundles cannot drift apart without a build failing.
 *
 * <p>The other half of the job is refusing to guess. itemDetails is a field any plugin may write,
 * and a misread payload would put a false legal statement on an invoice, so anything unmarked,
 * malformed or truncated has to read as absent rather than as a treatment.
 */
public class TestVatItemDetails {

    private static final String FULL =
            "{\"plugin\":\"killbill-vat\",\"treatment\":\"REVERSE_CHARGE\",\"jurisdiction\":\"DE\","
            + "\"rate\":\"0.0000\",\"supplierCountry\":\"GB\",\"customerCountry\":\"DE\","
            + "\"customerVatNumber\":\"DE123456789\"}";

    @Test(groups = "fast")
    public void testReadsEveryFieldThePluginWrites() {
        final VatItemDetails details = VatItemDetails.parse(FULL);

        assertNotNull(details);
        assertEquals(details.getTreatment(), "REVERSE_CHARGE");
        assertEquals(details.getJurisdiction(), "DE");
        assertEquals(details.getSupplierCountry(), "GB");
        assertEquals(details.getCustomerCountry(), "DE");
        assertEquals(details.getCustomerVatNumber(), "DE123456789");
    }

    /** The domestic shape, where the plugin omits the VAT number entirely. */
    @Test(groups = "fast")
    public void testAbsentFieldsReadAsNull() {
        final VatItemDetails details = VatItemDetails.parse(
                "{\"plugin\":\"killbill-vat\",\"treatment\":\"DOMESTIC\",\"jurisdiction\":\"GB\","
                + "\"rate\":\"0.2000\",\"supplierCountry\":\"GB\",\"customerCountry\":\"GB\"}");

        assertNotNull(details);
        assertEquals(details.getCustomerCountry(), "GB");
        assertNull(details.getCustomerVatNumber());
    }

    /**
     * Another plugin's payload in the same field. AvaTax and the like write itemDetails too, and
     * reading one of those as a VAT treatment would print a statement about tax that nobody
     * computed.
     */
    @Test(groups = "fast")
    public void testAnotherPluginsPayloadIsIgnored() {
        assertNull(VatItemDetails.parse(
                "{\"plugin\":\"killbill-avatax\",\"customerCountry\":\"DE\"}"));
        assertNull(VatItemDetails.parse("{\"taxCode\":\"P0000000\",\"customerCountry\":\"DE\"}"));
    }

    /** Nothing at all is the normal case for an invoice raised before this shipped. */
    @Test(groups = "fast")
    public void testEmptyReadsAsAbsent() {
        assertNull(VatItemDetails.parse(null));
        assertNull(VatItemDetails.parse(""));
    }

    /**
     * Truncated at the database column boundary, or otherwise mangled. This runs while an invoice
     * is being rendered, so it must return nothing rather than throw: losing a legend is a defect,
     * losing the invoice is an outage.
     */
    @Test(groups = "fast")
    public void testMalformedReadsAsAbsentAndNeverThrows() {
        // No usable marker: not ours, so nothing at all.
        assertNull(VatItemDetails.parse("{\"plugin\":"));
        assertNull(VatItemDetails.parse("not json at all"));
        assertNull(VatItemDetails.parse("{}"));

        // Marked as ours but truncated. Every field an unreadable payload cannot yield reads as
        // null individually, rather than the whole payload being discarded, because a partial
        // record is still worth what can be read from it. The caller checks the field it needs.
        final VatItemDetails truncated =
                VatItemDetails.parse("{\"plugin\":\"killbill-vat\",\"customerCountry\":\"D");
        assertNotNull(truncated);
        assertNull(truncated.getCustomerCountry());

        final VatItemDetails partial =
                VatItemDetails.parse("{\"plugin\":\"killbill-vat\",\"customerCountry\":\"DE\",\"rate\":");
        assertNotNull(partial);
        assertEquals(partial.getCustomerCountry(), "DE");
        assertNull(partial.getTreatment());
    }

    /** Whitespace around the colon, which a reformatted payload could carry. */
    @Test(groups = "fast")
    public void testTolerantOfWhitespace() {
        final VatItemDetails details = VatItemDetails.parse(
                "{ \"plugin\" : \"killbill-vat\" , \"customerCountry\" : \"FR\" }");

        assertNotNull(details);
        assertEquals(details.getCustomerCountry(), "FR");
    }

    /** The escapes the writer produces have to survive the round trip. */
    @Test(groups = "fast")
    public void testUnescapes() {
        final VatItemDetails details = VatItemDetails.parse(
                "{\"plugin\":\"killbill-vat\",\"jurisdiction\":\"a\\\"b\",\"customerCountry\":\"GB\"}");

        assertNotNull(details);
        assertEquals(details.getJurisdiction(), "a\"b");
    }

    /** An empty string value is not a value: it must not become an empty country on an invoice. */
    @Test(groups = "fast")
    public void testEmptyValueReadsAsNull() {
        final VatItemDetails details = VatItemDetails.parse(
                "{\"plugin\":\"killbill-vat\",\"customerCountry\":\"\",\"treatment\":\"DOMESTIC\"}");

        assertNotNull(details);
        assertNull(details.getCustomerCountry());
        assertEquals(details.getTreatment(), "DOMESTIC");
    }
}
