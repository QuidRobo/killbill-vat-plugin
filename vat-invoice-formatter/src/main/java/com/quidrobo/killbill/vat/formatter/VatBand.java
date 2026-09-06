/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.formatter;

import java.math.BigDecimal;
import java.util.Locale;

import org.killbill.billing.catalog.api.Currency;

/**
 * One row of the VAT summary: a rate, the net amount it applied to, and the VAT it produced.
 *
 * HMRC requires a full VAT invoice to show, for each rate, the net amount and the VAT charged.
 * Every getter here is reachable from the template inside
 * <code>{{#invoice.vatBreakdown}} ... {{/invoice.vatBreakdown}}</code>.
 */
public class VatBand {

    private final String description;
    private final BigDecimal netAmount;
    private final BigDecimal vatAmount;
    private final Currency currency;
    private final Locale locale;

    public VatBand(final String description,
                        final BigDecimal netAmount,
                        final BigDecimal vatAmount,
                        final Currency currency,
                        final Locale locale) {
        this.description = description;
        this.netAmount = VatInvoiceFormatter.safe(netAmount);
        this.vatAmount = VatInvoiceFormatter.safe(vatAmount);
        this.currency = currency;
        this.locale = locale;
    }

    /** The tax name as the tax engine supplied it, for example "GB VAT" or "VAT 20%". */
    public String getDescription() {
        return description;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public BigDecimal getVatAmount() {
        return vatAmount;
    }

    /** Rate derived from the amounts, since AvaTax does not put the percentage on the item. */
    public String getFormattedRate() {
        return VatInvoiceFormatter.VatMoney.ratePercent(netAmount, vatAmount);
    }

    public String getFormattedNetAmount() {
        return VatInvoiceFormatter.VatMoney.format(netAmount, currency, locale);
    }

    public String getFormattedVatAmount() {
        return VatInvoiceFormatter.VatMoney.format(vatAmount, currency, locale);
    }
}
