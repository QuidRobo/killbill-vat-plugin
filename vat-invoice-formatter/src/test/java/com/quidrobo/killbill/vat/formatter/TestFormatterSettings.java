/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.formatter;

import java.util.UUID;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/**
 * The formatter's two settings.
 *
 * The tenant id is the one that matters: a missing or unreadable one has to produce null, because
 * null is what makes the formatter suppress every VAT treatment legend instead of guessing at one.
 */
public class TestFormatterSettings {

    @Test(groups = "fast")
    public void testMissingSettingsFallBack() {
        System.clearProperty(FormatterSettings.TENANT_ID_PROPERTY);
        System.clearProperty(FormatterSettings.SUPPLIER_COUNTRY_PROPERTY);

        final FormatterSettings settings = FormatterSettings.load(null);

        assertNull(settings.getTenantId(),
                   "no tenant id must stay null, which is what suppresses the legends");
        assertEquals(settings.getSupplierCountry(), "GB");
    }

    @Test(groups = "fast")
    public void testSystemPropertiesAreRead() {
        final UUID tenantId = UUID.randomUUID();
        System.setProperty(FormatterSettings.TENANT_ID_PROPERTY, "  " + tenantId + "  ");
        System.setProperty(FormatterSettings.SUPPLIER_COUNTRY_PROPERTY, "ie");
        try {
            final FormatterSettings settings = FormatterSettings.load(null);

            assertEquals(settings.getTenantId(), tenantId, "surrounding whitespace is trimmed");
            assertEquals(settings.getSupplierCountry(), "IE", "and the country is upper-cased");
        } finally {
            System.clearProperty(FormatterSettings.TENANT_ID_PROPERTY);
            System.clearProperty(FormatterSettings.SUPPLIER_COUNTRY_PROPERTY);
        }
    }

    @Test(groups = "fast")
    public void testAnUnreadableTenantIdIsNotFatal() {
        // Rendering an invoice without a legend beats failing the render outright.
        System.setProperty(FormatterSettings.TENANT_ID_PROPERTY, "not-a-uuid");
        try {
            assertNull(FormatterSettings.load(null).getTenantId());
        } finally {
            System.clearProperty(FormatterSettings.TENANT_ID_PROPERTY);
        }
    }
}
