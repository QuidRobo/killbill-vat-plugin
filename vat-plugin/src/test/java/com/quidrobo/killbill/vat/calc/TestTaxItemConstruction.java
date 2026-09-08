/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.calc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.UUID;

import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.testng.annotations.Test;

import com.quidrobo.killbill.vat.resolve.VatTreatment;
import com.quidrobo.killbill.vat.resolve.VatTreatmentKind;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * That a tax item actually comes out, and actually carries the VAT record.
 *
 * <h2>Why this test exists</h2>
 *
 * The zero-VAT item was written, reviewed, documented and shipped past a green build while doing
 * nothing at all. {@code PluginTaxCalculator.buildTaxItem} opens with
 * {@code if (amount == null || ZERO.compareTo(amount) == 0) return null;}, so every reverse charge
 * and outside-scope supply produced no item, and the formatter had nothing to read. Every test
 * around it passed: the writer was tested, the reader was tested, the round trip between them was
 * tested. Nothing tested the one call that decides whether an item exists.
 *
 * <p>So this tests that call, on both sides of zero, and asserts the itemDetails is on the item
 * rather than merely producible.
 */
public class TestTaxItemConstruction {

    private static final UUID INVOICE_ID = UUID.randomUUID();

    /** A charge, answering the handful of things item construction reads off it. */
    private static InvoiceItem charge() {
        final UUID id = UUID.randomUUID();
        final UUID accountId = UUID.randomUUID();
        return (InvoiceItem) Proxy.newProxyInstance(
                TestTaxItemConstruction.class.getClassLoader(),
                new Class<?>[] {InvoiceItem.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args) {
                        switch (method.getName()) {
                            case "getId": return id;
                            case "getAccountId": return accountId;
                            case "getInvoiceId": return INVOICE_ID;
                            case "getInvoiceItemType": return InvoiceItemType.RECURRING;
                            case "getAmount": return new BigDecimal("100.00");
                            case "getCurrency": return Currency.GBP;
                            // Dates are copied straight through by item construction and are
                            // not what this test is about, so null keeps it independent of the
                            // joda version on the classpath.
                            case "getStartDate": return null;
                            case "getEndDate": return null;
                            case "getDescription": return "Pro plan";
                            case "getPlanName": return "pro-monthly";
                            case "getProductName": return "Pro";
                            case "hashCode": return Integer.valueOf(System.identityHashCode(proxy));
                            case "equals": return Boolean.valueOf(proxy == args[0]);
                            case "toString": return "charge";
                            default: return null;
                        }
                    }
                });
    }

    private static VatTreatment domestic() {
        return VatTreatment.charging(VatTreatmentKind.DOMESTIC, "GB",
                                     new BigDecimal("0.20"), "VAT 20%", "domestic");
    }

    private static VatTreatment reverseCharge() {
        return VatTreatment.reverseCharge("DE", "DE123456789", "Reverse charge", "EU B2B");
    }

    // ------------------------------------------------------------- charging

    @Test(groups = "fast")
    public void testChargingSupplyProducesATaxItemCarryingTheVat() {
        final InvoiceItem tax = VatTaxCalculator.taxItemFor(
                charge(), INVOICE_ID, new BigDecimal("20.00"), domestic(), "GB", "GB", null, true);

        assertNotNull(tax);
        assertEquals(tax.getInvoiceItemType(), InvoiceItemType.TAX);
        assertEquals(tax.getAmount(), new BigDecimal("20.00"));
        assertEquals(tax.getDescription(), "VAT 20%");
    }

    @Test(groups = "fast")
    public void testChargingSupplyCarriesTheVatRecord() {
        final InvoiceItem tax = VatTaxCalculator.taxItemFor(
                charge(), INVOICE_ID, new BigDecimal("20.00"), domestic(), "GB", "GB", null, true);

        assertNotNull(tax.getItemDetails());
        assertTrue(tax.getItemDetails().contains("\"treatment\":\"DOMESTIC\""), tax.getItemDetails());
        assertTrue(tax.getItemDetails().contains("\"customerCountry\":\"GB\""), tax.getItemDetails());
    }

    // ------------------------------------------------------------- zero VAT

    /**
     * The one that was silently broken. A reverse charge carries no money and must still carry a
     * record, or the invoice has no way of saying why it charged nothing.
     */
    @Test(groups = "fast")
    public void testZeroVatSupplyStillProducesATaxItem() {
        final InvoiceItem tax = VatTaxCalculator.taxItemFor(
                charge(), INVOICE_ID, BigDecimal.ZERO, reverseCharge(),
                "GB", "DE", "DE123456789", true);

        assertNotNull(tax, "a zero-VAT supply must still record its treatment");
        assertEquals(tax.getInvoiceItemType(), InvoiceItemType.TAX);
        assertEquals(tax.getAmount().compareTo(BigDecimal.ZERO), 0);
    }

    @Test(groups = "fast")
    public void testZeroVatItemCarriesTheTreatmentAndVatNumber() {
        final InvoiceItem tax = VatTaxCalculator.taxItemFor(
                charge(), INVOICE_ID, BigDecimal.ZERO, reverseCharge(),
                "GB", "DE", "DE123456789", true);

        assertNotNull(tax.getItemDetails());
        assertTrue(tax.getItemDetails().contains("\"treatment\":\"REVERSE_CHARGE\""), tax.getItemDetails());
        assertTrue(tax.getItemDetails().contains("\"customerVatNumber\":\"DE123456789\""), tax.getItemDetails());
        assertTrue(tax.getItemDetails().contains("\"customerCountry\":\"DE\""), tax.getItemDetails());
    }

    /**
     * A reverse charge treatment carries no description, and that path never reached item
     * construction before. It must not produce a null description or blow up.
     */
    @Test(groups = "fast")
    public void testZeroVatItemGetsADescriptionEvenThoughTheTreatmentHasNone() {
        assertNull(reverseCharge().getDescription(), "precondition: the treatment has none");

        final InvoiceItem tax = VatTaxCalculator.taxItemFor(
                charge(), INVOICE_ID, BigDecimal.ZERO, reverseCharge(), "GB", "DE", "DE1", true);

        assertEquals(tax.getDescription(), "VAT");
    }

    @Test(groups = "fast")
    public void testOutsideScopeAlsoRecords() {
        final InvoiceItem tax = VatTaxCalculator.taxItemFor(
                charge(), INVOICE_ID, BigDecimal.ZERO,
                VatTreatment.outsideScope("US", "Outside scope", "non-EU"),
                "GB", "US", null, true);

        assertNotNull(tax);
        assertTrue(tax.getItemDetails().contains("\"treatment\":\"OUTSIDE_SCOPE\""), tax.getItemDetails());
    }

    // ------------------------------------------------------------- the kill switch

    /** Off means back to the old behaviour: no item, and no treatment recorded. */
    @Test(groups = "fast")
    public void testZeroVatItemIsNotEmittedWhenRecordingIsSwitchedOff() {
        assertNull(VatTaxCalculator.taxItemFor(
                charge(), INVOICE_ID, BigDecimal.ZERO, reverseCharge(), "GB", "DE", "DE1", false));
    }

    /** The switch only governs the zero case. A real charge is never suppressed by it. */
    @Test(groups = "fast")
    public void testChargingSupplyIsUnaffectedByTheKillSwitch() {
        assertNotNull(VatTaxCalculator.taxItemFor(
                charge(), INVOICE_ID, new BigDecimal("20.00"), domestic(), "GB", "GB", null, false));
    }

    // ------------------------------------------------------------- edges

    @Test(groups = "fast")
    public void testNullVatProducesNothing() {
        assertNull(VatTaxCalculator.taxItemFor(
                charge(), INVOICE_ID, null, domestic(), "GB", "GB", null, true));
    }

    /** The tax item must link back to the charge it taxes, or the VAT summary loses its base. */
    @Test(groups = "fast")
    public void testTaxItemLinksToTheChargeItTaxes() {
        final InvoiceItem taxable = charge();
        final InvoiceItem tax = VatTaxCalculator.taxItemFor(
                taxable, INVOICE_ID, new BigDecimal("20.00"), domestic(), "GB", "GB", null, true);

        assertEquals(tax.getLinkedItemId(), taxable.getId());
        assertEquals(tax.getInvoiceId(), INVOICE_ID);
    }
}
