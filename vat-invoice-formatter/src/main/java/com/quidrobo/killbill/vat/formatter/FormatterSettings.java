/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.formatter;

import java.util.UUID;

import org.killbill.billing.osgi.libs.killbill.OSGIConfigPropertiesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the formatter needs that Kill Bill 0.24.x does not hand it.
 *
 * <h2>tenantId is a legacy fallback, not a requirement</h2>
 *
 * The 0.24.x {@code InvoiceFormatterFactory} signature carries no {@code TenantContext}, so a
 * formatter cannot look up account custom fields on its own. That used to mean configuring one
 * tenant id here, which is wrong on a server hosting more than one tenant, and Kill Bill is multi
 * tenanted by design.
 *
 * <p>It is no longer how this works. The plugin records the VAT treatment, the customer's country
 * and their VAT number onto each tax item's {@code itemDetails} while it still holds a tenant
 * context, Kill Bill persists that on {@code invoice_items}, and the formatter reads it back off
 * the invoice it is handed. No tenant, no configuration, no lookup, and correct for every tenant on
 * the server. See {@link VatItemDetails}.
 *
 * <p>{@link #TENANT_ID_PROPERTY} therefore only affects invoices raised before the plugin began
 * recording this. Setting it makes those historical invoices render their legend, for the one
 * tenant named; leaving it unset makes them render without one. Neither choice affects a current
 * invoice. On a multi tenanted server, leave it unset.
 *
 * <p>The supplier country is configured for the reason the calculator makes it configurable: this
 * plugin is not a UK-only plugin, and deciding "overseas" against a hardcoded {@code GB} would
 * silently mislabel every invoice issued by a supplier established anywhere else.
 *
 * Both are read from {@code killbill.properties} first and from a JVM system property second, so
 * either deployment style works:
 *
 * <pre>
 * org.killbill.billing.plugin.vat.formatter.tenantId=&lt;uuid&gt;
 * org.killbill.billing.plugin.vat.formatter.supplierCountry=GB
 * </pre>
 */
public final class FormatterSettings {

    private static final Logger logger = LoggerFactory.getLogger(FormatterSettings.class);

    /** Kill Bill tenant id, needed to read account custom fields on 0.24.x. */
    public static final String TENANT_ID_PROPERTY =
            "org.killbill.billing.plugin.vat.formatter.tenantId";

    /** Country the supplier is established in. Anything else counts as overseas. */
    public static final String SUPPLIER_COUNTRY_PROPERTY =
            "org.killbill.billing.plugin.vat.formatter.supplierCountry";

    private static final String DEFAULT_SUPPLIER_COUNTRY = "GB";

    private final UUID tenantId;
    private final String supplierCountry;

    FormatterSettings(final UUID tenantId, final String supplierCountry) {
        this.tenantId = tenantId;
        this.supplierCountry = supplierCountry;
    }

    public static FormatterSettings load(final OSGIConfigPropertiesService configProperties) {
        return new FormatterSettings(parseTenantId(lookup(configProperties, TENANT_ID_PROPERTY)),
                                     country(lookup(configProperties, SUPPLIER_COUNTRY_PROPERTY)));
    }

    /** Null when no tenant id is configured, in which case no custom field can be read. */
    public UUID getTenantId() {
        return tenantId;
    }

    /** Upper case ISO 3166-1 alpha-2. Never null; defaults to GB. */
    public String getSupplierCountry() {
        return supplierCountry;
    }

    private static String lookup(final OSGIConfigPropertiesService configProperties, final String key) {
        String value = null;
        if (configProperties != null) {
            try {
                value = configProperties.getString(key);
            } catch (final RuntimeException e) {
                logger.warn("Unable to read {} from the Kill Bill configuration", key, e);
            }
        }
        if (value == null || value.trim().isEmpty()) {
            value = System.getProperty(key);
        }
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static UUID parseTenantId(final String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (final IllegalArgumentException e) {
            logger.warn("{} is not a valid UUID: {}. Invoices will render without the customer VAT"
                        + " number and without a VAT treatment legend.", TENANT_ID_PROPERTY, value);
            return null;
        }
    }

    private static String country(final String value) {
        return value == null ? DEFAULT_SUPPLIER_COUNTRY : value.toUpperCase();
    }
}
