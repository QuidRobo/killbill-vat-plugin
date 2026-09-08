/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.formatter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.killbill.billing.invoice.api.InvoiceItem;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

/**
 * Deciding the customer's country and VAT number from the invoice alone.
 *
 * <p>This is the whole point of the multi tenant formatter: on Kill Bill 0.24.x a formatter gets no
 * tenant context and cannot read the account, so what it can say about a supply has to come off the
 * invoice it was handed. The plugin writes that onto the tax items while it does have a tenant.
 *
 * <p>What is decided here ends up on a legal document. A reverse charge invoice must carry the
 * customer's VAT number, and a zero-rated one must not claim to be a reverse charge, so the rule
 * for "I cannot tell" matters as much as the rule for "I can".
 */
public class TestCustomerFactsFromTaxItems {

    /**
     * A tax item that answers only {@code getItemDetails}, via a proxy.
     *
     * <p>InvoiceItem has around thirty methods and this needs one of them. A proxy says that
     * plainly; thirty stubbed returns would bury it.
     */
    private static InvoiceItem taxItem(final String itemDetails) {
        return (InvoiceItem) Proxy.newProxyInstance(
                TestCustomerFactsFromTaxItems.class.getClassLoader(),
                new Class<?>[] {InvoiceItem.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args) {
                        if ("getItemDetails".equals(method.getName())) {
                            return itemDetails;
                        }
                        if ("toString".equals(method.getName())) {
                            return "taxItem(" + itemDetails + ")";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return Integer.valueOf(System.identityHashCode(proxy));
                        }
                        if ("equals".equals(method.getName())) {
                            return Boolean.valueOf(proxy == args[0]);
                        }
                        return null;
                    }
                });
    }

    private static String details(final String country, final String vatNumber, final String treatment) {
        final StringBuilder json = new StringBuilder("{\"plugin\":\"killbill-vat\"");
        json.append(",\"treatment\":\"").append(treatment).append('"');
        if (country != null) {
            json.append(",\"customerCountry\":\"").append(country).append('"');
        }
        if (vatNumber != null) {
            json.append(",\"customerVatNumber\":\"").append(vatNumber).append('"');
        }
        return json.append('}').toString();
    }

    private static List<InvoiceItem> items(final String... itemDetails) {
        final List<InvoiceItem> list = new ArrayList<InvoiceItem>();
        for (final String d : itemDetails) {
            list.add(taxItem(d));
        }
        return list;
    }

    @Test(groups = "fast")
    public void testReadsCountryAndVatNumberOffTheInvoice() {
        final VatInvoiceFormatter.CustomerFacts facts = VatInvoiceFormatter.factsFrom(
                items(details("DE", "DE123456789", "REVERSE_CHARGE")), UUID.randomUUID());

        assertNotNull(facts);
        assertEquals(facts.country, "DE");
        assertEquals(facts.vatNumber, "DE123456789");
        assertEquals(Boolean.valueOf(facts.available), Boolean.TRUE);
    }

    /** A domestic supply has no VAT number and that is not a failure to read one. */
    @Test(groups = "fast")
    public void testDomesticSupplyHasCountryAndNoVatNumber() {
        final VatInvoiceFormatter.CustomerFacts facts = VatInvoiceFormatter.factsFrom(
                items(details("GB", null, "DOMESTIC")), UUID.randomUUID());

        assertNotNull(facts);
        assertEquals(facts.country, "GB");
        assertNull(facts.vatNumber);
    }

    @Test(groups = "fast")
    public void testCountryIsUpperCased() {
        assertEquals(VatInvoiceFormatter.factsFrom(
                items(details("fr", null, "DESTINATION")), UUID.randomUUID()).country, "FR");
    }

    /**
     * Several charges on one invoice, all the same supply. The VAT number is taken from whichever
     * item carries it rather than requiring every item to repeat it.
     */
    @Test(groups = "fast")
    public void testAgreeingItemsCollapseToOneAnswer() {
        final VatInvoiceFormatter.CustomerFacts facts = VatInvoiceFormatter.factsFrom(
                items(details("IE", null, "REVERSE_CHARGE"),
                      details("IE", "IE1234567X", "REVERSE_CHARGE"),
                      details("IE", null, "REVERSE_CHARGE")),
                UUID.randomUUID());

        assertNotNull(facts);
        assertEquals(facts.country, "IE");
        assertEquals(facts.vatNumber, "IE1234567X");
    }

    /**
     * Two countries on one invoice is not something to average out. One invoice is one supply to
     * one customer, so this means something upstream is wrong, and printing a place-of-supply
     * statement chosen by iteration order would be a false statement on a legal document.
     */
    @Test(groups = "fast")
    public void testDisagreeingCountriesYieldNothing() {
        assertNull(VatInvoiceFormatter.factsFrom(
                items(details("DE", null, "REVERSE_CHARGE"),
                      details("FR", null, "DESTINATION")),
                UUID.randomUUID()));
    }

    /** Nothing to read: an invoice raised before the plugin recorded any of this. */
    @Test(groups = "fast")
    public void testNoDetailsYieldsNothingSoTheCallerCanFallBack() {
        assertNull(VatInvoiceFormatter.factsFrom(items((String) null), UUID.randomUUID()));
        assertNull(VatInvoiceFormatter.factsFrom(items(), UUID.randomUUID()));
        assertNull(VatInvoiceFormatter.factsFrom(null, UUID.randomUUID()));
    }

    /** Another plugin's payload in the same field must not be read as a VAT decision. */
    @Test(groups = "fast")
    public void testForeignPayloadYieldsNothing() {
        assertNull(VatInvoiceFormatter.factsFrom(
                items("{\"plugin\":\"killbill-avatax\",\"customerCountry\":\"DE\"}"),
                UUID.randomUUID()));
    }

    /**
     * A recorded treatment with no country is not usable: place of supply is the one thing every
     * legend depends on.
     */
    @Test(groups = "fast")
    public void testTreatmentWithoutCountryYieldsNothing() {
        assertNull(VatInvoiceFormatter.factsFrom(
                items(details(null, "DE123456789", "REVERSE_CHARGE")), UUID.randomUUID()));
    }

    /** Items with nothing recorded are skipped rather than blocking the ones that do. */
    @Test(groups = "fast")
    public void testUnrecordedItemsAreSkipped() {
        final List<InvoiceItem> mixed = new ArrayList<InvoiceItem>(
                Arrays.asList(taxItem(null),
                              taxItem("{\"plugin\":\"killbill-avatax\"}"),
                              taxItem(details("ES", "ESX1234567", "REVERSE_CHARGE"))));

        final VatInvoiceFormatter.CustomerFacts facts =
                VatInvoiceFormatter.factsFrom(mixed, UUID.randomUUID());

        assertNotNull(facts);
        assertEquals(facts.country, "ES");
        assertEquals(facts.vatNumber, "ESX1234567");
    }

    /** A null id is what an invoice under construction would pass; it must not throw. */
    @Test(groups = "fast")
    public void testNullInvoiceIdIsTolerated() {
        assertNull(VatInvoiceFormatter.factsFrom(
                items(details("DE", null, "REVERSE_CHARGE"), details("FR", null, "DESTINATION")), null));
    }
}
