/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.core;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/**
 * Reading org.killbill.invoice.plugin out of the per-tenant configuration.
 *
 * Kill Bill only calls getAdditionalInvoiceItems on plugins named in that property. A plugin that
 * is installed, started, healthy and correctly configured but missing from the list is never asked
 * to tax anything: every invoice comes out at 0% VAT, nothing is logged, and the plugin's own
 * /simulate keeps answering correctly because it never touches the invoice pipeline. That gap cost
 * a real invoice its VAT, which is why the parsing is tested rather than eyeballed.
 */
public class TestInvoicePluginRegistration {

    private static final String KEY = VatConfigurationHandler.INVOICE_PLUGIN_PROPERTY;

    @Test(groups = "fast")
    public void testFindsTheOnlyRegisteredPlugin() {
        assertEquals(VatConfigurationHandler.extractJsonStringValue(
                "{\"org.killbill.invoice.plugin\":\"killbill-vat\"}", KEY), "killbill-vat");
    }

    @Test(groups = "fast")
    public void testFindsTheValueAmongOtherProperties() {
        final String json = "{\"org.killbill.catalog.uri\":\"catalog.xml\","
                            + "\"org.killbill.invoice.plugin\":\"killbill-vat,other-plugin\","
                            + "\"org.killbill.payment.retry.days\":\"1,3,5\"}";
        assertEquals(VatConfigurationHandler.extractJsonStringValue(json, KEY),
                     "killbill-vat,other-plugin");
    }

    @Test(groups = "fast")
    public void testWhitespaceAroundTheJsonColonIsTolerated() {
        assertEquals(VatConfigurationHandler.extractJsonStringValue(
                "{ \"org.killbill.invoice.plugin\" : \"killbill-vat\" }", KEY), "killbill-vat");
    }

    /** A per-tenant config that configures other things but registers no invoice plugin. */
    @Test(groups = "fast")
    public void testAbsentPropertyIsNull() {
        assertNull(VatConfigurationHandler.extractJsonStringValue(
                "{\"org.killbill.payment.retry.days\":\"1,3,5\"}", KEY));
    }

    /**
     * A malformed payload must read as "cannot tell", never as an exception: this runs on the
     * diagnostics path, and throwing there would hide the very problem it exists to report.
     */
    @Test(groups = "fast")
    public void testTruncatedJsonIsNull() {
        assertNull(VatConfigurationHandler.extractJsonStringValue(
                "{\"org.killbill.invoice.plugin\":", KEY));
        assertNull(VatConfigurationHandler.extractJsonStringValue(
                "{\"org.killbill.invoice.plugin\":\"killbill-vat", KEY));
        assertNull(VatConfigurationHandler.extractJsonStringValue("", KEY));
    }

    /**
     * A property whose name merely starts with the one we want must not match. The closing quote
     * in the search is what prevents it, and without a test that is easy to lose in a refactor:
     * matching a longer key would report the plugin registered when it is not.
     */
    @Test(groups = "fast")
    public void testLongerPropertyNameDoesNotMatch() {
        assertNull(VatConfigurationHandler.extractJsonStringValue(
                "{\"org.killbill.invoice.pluginsExtra\":\"killbill-vat\"}", KEY));
    }

    // ------------------------------------------------------------------ the decision itself

    @Test(groups = "fast")
    public void testRegisteredWhenNamedAlone() {
        assertEquals(VatConfigurationHandler.registrationFrom(
                "{\"org.killbill.invoice.plugin\":\"killbill-vat\"}", "killbill-vat"), Boolean.TRUE);
    }

    @Test(groups = "fast")
    public void testRegisteredWhenNamedAmongOthers() {
        assertEquals(VatConfigurationHandler.registrationFrom(
                "{\"org.killbill.invoice.plugin\":\"other-plugin, killbill-vat ,third\"}",
                "killbill-vat"), Boolean.TRUE);
    }

    /**
     * The expensive case. A per-tenant config that registers a DIFFERENT invoice plugin is the
     * easiest way to end up with 0% VAT while believing everything is configured.
     */
    @Test(groups = "fast")
    public void testNotRegisteredWhenOnlyAnotherPluginIsListed() {
        assertEquals(VatConfigurationHandler.registrationFrom(
                "{\"org.killbill.invoice.plugin\":\"some-other-tax-plugin\"}", "killbill-vat"),
                Boolean.FALSE);
    }

    /** A substring of another plugin's name must not count as registration. */
    @Test(groups = "fast")
    public void testNotRegisteredOnAPartialNameMatch() {
        assertEquals(VatConfigurationHandler.registrationFrom(
                "{\"org.killbill.invoice.plugin\":\"killbill-vat-experimental\"}", "killbill-vat"),
                Boolean.FALSE);
    }

    /**
     * A read that succeeded and found no per-tenant config is a definite "not registered": Kill
     * Bill calls no invoice plugin that is not named in that property.
     */
    @Test(groups = "fast")
    public void testNoPerTenantConfigAtAllIsNotRegistered() {
        assertEquals(VatConfigurationHandler.registrationFrom(null, "killbill-vat"), Boolean.FALSE);
        assertEquals(VatConfigurationHandler.registrationFrom("{}", "killbill-vat"), Boolean.FALSE);
    }

    /**
     * A tenant that cannot be resolved is unknown, not unregistered. /config omits the field
     * entirely rather than printing a hint that tells the operator to REPLACE their whole
     * per-tenant config, which would wipe properties that are already there.
     */
    @Test(groups = "fast")
    public void testNoTenantIsUnknownRatherThanNotRegistered() {
        final VatConfigurationHandler handler = new VatConfigurationHandler("killbill-vat", null);
        assertNull(handler.isRegisteredAsInvoicePlugin(null));
        assertNull(handler.isRegisteredAsInvoicePlugin(java.util.UUID.randomUUID()));
    }
}
