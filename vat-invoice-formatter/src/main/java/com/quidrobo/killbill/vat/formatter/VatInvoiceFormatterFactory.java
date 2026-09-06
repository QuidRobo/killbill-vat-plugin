/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.formatter;

import java.util.Locale;
import java.util.ResourceBundle;

import org.killbill.billing.currency.api.CurrencyConversionApi;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.formatters.InvoiceFormatter;
import org.killbill.billing.invoice.plugin.api.InvoiceFormatterFactory;
import org.killbill.billing.osgi.libs.killbill.OSGIConfigPropertiesService;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;

/**
 * Hands Kill Bill a {@link VatInvoiceFormatter} instead of the built-in one.
 *
 * This is the 7 argument signature, which is what killbill-plugin-api 0.27.x (Kill Bill 0.24.x)
 * declares. Kill Bill 0.25 and later add an 8 argument overload carrying a TenantContext; if you
 * upgrade, implement that one and drop the configured tenant id that {@link FormatterSettings}
 * falls back on.
 *
 * Settings are read once here rather than per invoice: they come from static configuration, and
 * an invoice run renders many invoices.
 */
public class VatInvoiceFormatterFactory implements InvoiceFormatterFactory {

    private final OSGIKillbillAPI killbillAPI;
    private final FormatterSettings settings;

    public VatInvoiceFormatterFactory(final OSGIKillbillAPI killbillAPI,
                                      final OSGIConfigPropertiesService configProperties) {
        this.killbillAPI = killbillAPI;
        this.settings = FormatterSettings.load(configProperties);
    }

    @Override
    public InvoiceFormatter createInvoiceFormatter(final String defaultLocale,
                                                   final String catalogBundlePath,
                                                   final Invoice invoice,
                                                   final Locale locale,
                                                   final CurrencyConversionApi currencyConversionApi,
                                                   final ResourceBundle bundle,
                                                   final ResourceBundle defaultBundle) {
        return new VatInvoiceFormatter(defaultLocale, catalogBundlePath, invoice, locale,
                                       currencyConversionApi, bundle, defaultBundle, killbillAPI,
                                       settings);
    }
}
