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
import java.util.ResourceBundle;
import java.util.UUID;

import org.joda.time.format.DateTimeFormatter;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.template.formatters.DefaultInvoiceItemFormatter;

/**
 * A charge line that knows its own VAT.
 *
 * A UK VAT invoice has to show the rate of VAT against each description, so each line carries the
 * VAT that the tax engine linked back to it via {@code linkedItemId}.
 *
 * Two stock behaviours are corrected here: {@code DefaultInvoiceItemFormatter} hardcodes
 * {@code getRate()} to zero and {@code getLinkedItemId()} to null, which makes the unit price and
 * the tax linkage invisible to any template.
 */
public class VatInvoiceItemFormatter extends DefaultInvoiceItemFormatter {

    private final InvoiceItem item;
    private final DateTimeFormatter dateFormatter;
    private final Locale locale;
    private final BigDecimal vatAmount;
    private final Currency invoiceCurrency;

    public VatInvoiceItemFormatter(final String defaultLocale,
                                     final String catalogBundlePath,
                                     final InvoiceItem item,
                                     final DateTimeFormatter dateFormatter,
                                     final Locale locale,
                                     final ResourceBundle bundle,
                                     final ResourceBundle defaultBundle,
                                     final BigDecimal vatAmount,
                                     final Currency invoiceCurrency) {
        super(defaultLocale, catalogBundlePath, item, dateFormatter, locale, bundle, defaultBundle);
        this.item = item;
        this.dateFormatter = dateFormatter;
        this.locale = locale;
        this.vatAmount = VatInvoiceFormatter.safe(vatAmount);
        this.invoiceCurrency = invoiceCurrency;
    }

    // ------------------------------------------------------------------
    // Stock behaviour that needs correcting
    // ------------------------------------------------------------------

    /** DefaultInvoiceItemFormatter returns BigDecimal.ZERO here, hiding the unit price. */
    @Override
    public BigDecimal getRate() {
        return item.getRate();
    }

    /** DefaultInvoiceItemFormatter returns null here, hiding the tax linkage. */
    @Override
    public UUID getLinkedItemId() {
        return item.getLinkedItemId();
    }

    /** Formatted through the shared helper so lines and totals render identically. */
    @Override
    public String getFormattedAmount() {
        return VatInvoiceFormatter.VatMoney.format(VatInvoiceFormatter.safe(item.getAmount()),
                                                       invoiceCurrency, locale);
    }

    /**
     * Kill Bill stores the service period end exclusively, so a month billed on the 1st ends on
     * the 1st of the next month. Showing that on an invoice reads as an extra day, so the
     * displayed end date is pulled back by one.
     */
    @Override
    public String getFormattedEndDate() {
        return item.getEndDate() == null ? null : item.getEndDate().minusDays(1).toString(dateFormatter);
    }

    // ------------------------------------------------------------------
    // VAT
    // ------------------------------------------------------------------

    public BigDecimal getVatAmount() {
        return vatAmount;
    }

    public String getFormattedVatAmount() {
        return VatInvoiceFormatter.VatMoney.format(vatAmount, invoiceCurrency, locale);
    }

    /** "20%", "0%", or blank when the line has no meaningful base to derive a rate from. */
    public String getVatRateLabel() {
        return VatInvoiceFormatter.VatMoney.ratePercent(VatInvoiceFormatter.safe(item.getAmount()), vatAmount);
    }

    /** The line amount excluding VAT. Catalogue prices are VAT exclusive, so this is the item amount. */
    public String getFormattedNetAmount() {
        return getFormattedAmount();
    }

    public String getFormattedGrossAmount() {
        return VatInvoiceFormatter.VatMoney.format(
                VatInvoiceFormatter.safe(item.getAmount()).add(vatAmount), invoiceCurrency, locale);
    }

    // ------------------------------------------------------------------
    // Presentation
    // ------------------------------------------------------------------

    /**
     * A description that is never blank. Recurring items usually carry no description of their
     * own, so this falls back through the catalogue names before giving up on the item type.
     */
    public String getLineDescription() {
        final String description = getDescription();
        if (isPresent(description)) {
            return description;
        }
        if (isPresent(getPrettyPlanName())) {
            return getPrettyPlanName();
        }
        if (isPresent(getPlanName())) {
            return getPlanName();
        }
        if (isPresent(getPrettyProductName())) {
            return getPrettyProductName();
        }
        if (isPresent(getProductName())) {
            return getProductName();
        }
        return item.getInvoiceItemType() == null ? "Charge" : item.getInvoiceItemType().toString();
    }

    /** A second line under the description, used when the plan name adds something to it. */
    public String getLineSubDescription() {
        final String primary = getLineDescription();
        final String plan = isPresent(getPrettyPlanName()) ? getPrettyPlanName() : getPlanName();
        if (isPresent(plan) && !plan.equals(primary)) {
            return plan;
        }
        return null;
    }

    /** Blank rather than "1" for the many items that carry no quantity at all. */
    public String getFormattedQuantity() {
        final BigDecimal quantity = item.getQuantity();
        if (quantity == null) {
            return "";
        }
        return quantity.stripTrailingZeros().toPlainString();
    }

    /** Unit price excluding VAT, blank when the item has no rate (adjustments, credits, usage). */
    public String getFormattedUnitPrice() {
        final BigDecimal rate = item.getRate();
        if (rate == null || rate.compareTo(BigDecimal.ZERO) == 0) {
            return "";
        }
        return VatInvoiceFormatter.VatMoney.format(rate, invoiceCurrency, locale);
    }

    private static boolean isPresent(final String value) {
        return value != null && !value.trim().isEmpty();
    }
}
