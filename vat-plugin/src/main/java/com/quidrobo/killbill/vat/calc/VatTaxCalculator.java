/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceApiException;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.plugin.api.invoice.PluginInvoiceItem;
import org.killbill.billing.plugin.api.invoice.PluginTaxCalculator;
import org.killbill.billing.util.callcontext.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.quidrobo.killbill.vat.api.CustomerProfile;
import com.quidrobo.killbill.vat.core.PriceMode;
import com.quidrobo.killbill.vat.core.VatConfig;
import com.quidrobo.killbill.vat.core.VatRuntime;
import com.quidrobo.killbill.vat.resolve.VatTreatment;
import com.quidrobo.killbill.vat.resolve.VatTreatmentRequest;

/**
 * Turns taxable invoice items into TAX items, and, for VAT-inclusive pricing, rewrites the charge
 * line down to its net amount.
 *
 * Extends {@link PluginTaxCalculator} purely to reuse {@code computeTaxItems}, which handles the
 * awkward parts: finding taxable items, matching adjustments to them, and following REPAIR_ADJ
 * items back to a previous invoice. That class declares no abstract methods, so this subclass
 * only adds behaviour.
 */
public class VatTaxCalculator extends PluginTaxCalculator {

    private static final Logger logger = LoggerFactory.getLogger(VatTaxCalculator.class);

    public VatTaxCalculator(final OSGIKillbillAPI osgiKillbillAPI) {
        super(osgiKillbillAPI);
    }

    /**
     * @param alreadyTaxed items that already carry tax, so they are not taxed twice
     * @return the items to add to the invoice: TAX items, plus rewritten charge lines in
     *         inclusive mode. Never null.
     */
    public List<InvoiceItem> compute(final Invoice invoice,
                                     final CustomerProfile customer,
                                     final VatRuntime runtime,
                                     final Map<java.util.UUID, Set<java.util.UUID>> alreadyTaxed,
                                     final TenantContext tenantContext) throws InvoiceApiException {
        final List<InvoiceItem> additionalItems = new ArrayList<InvoiceItem>();
        final VatConfig config = runtime.getConfig();

        for (final NewItemToTax newItem : computeTaxItems(invoice, alreadyTaxed, tenantContext)) {
            if (newItem.isReturnOnly()) {
                // The taxable item was taxed on a previous run and only its adjustments are new.
                // Refunding tax on an adjustment requires knowing which adjustments were already
                // accounted for, which needs persisted plugin state. Until that exists these are
                // skipped rather than guessed at, because guessing here would refund the same
                // adjustment on every subsequent invoice run.
                logger.debug("Skipping adjustment-only tax for item {} until persisted tax state exists",
                             newItem.getTaxableItem().getId());
                continue;
            }

            final InvoiceItem taxable = newItem.getTaxableItem();
            final List<InvoiceItem> adjustments = newItem.getAdjustmentItems();
            final BigDecimal taxableAmount = netAmount(taxable, adjustments);
            if (taxableAmount == null || taxableAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            final VatTreatment treatment = runtime.getResolver().resolve(
                    request(customer, taxable, invoice));

            final boolean adjusted = adjustments != null && !adjustments.isEmpty();
            final PriceMode configuredMode =
                    config.getPriceMode(taxable.getPlanName(), taxable.getProductName());
            final PriceMode priceMode = effectivePriceMode(configuredMode, adjusted);
            if (priceMode != configuredMode) {
                logger.warn("Item {} is priced VAT-inclusive but already carries adjustments;"
                            + " leaving its amount at {} and charging VAT on top instead",
                            taxable.getId(), taxable.getAmount());
            }

            logger.debug("VAT for item {} ({}): {} priceMode={}",
                         taxable.getId(), taxable.getPlanName(), treatment, priceMode);

            final VatComputation.VatSplit split = VatComputation.split(taxableAmount,
                                                                      treatment.getRate(),
                                                                      priceMode,
                                                                      config.getRoundingScale(),
                                                                      config.getRoundingMode());

            if (split.getVat().compareTo(BigDecimal.ZERO) != 0) {
                final InvoiceItem taxItem = buildTaxItem(taxable, invoice.getId(), null,
                                                         split.getVat(), treatment.getDescription());
                if (taxItem != null) {
                    additionalItems.add(taxItem);
                }
            }

            if (priceMode == PriceMode.INCLUSIVE && split.requiresRewrite(taxable.getAmount())) {
                // Logged at INFO because the plugin cannot see whether Kill Bill actually applied
                // the rewrite. If it were ever dropped, the invoice would keep the gross charge
                // AND the tax item, and this line is the only way to spot that afterwards.
                logger.info("Rewriting VAT-inclusive item {} from {} to net {}, tax {}",
                            taxable.getId(), taxable.getAmount(), split.getNet(), split.getVat());
                additionalItems.add(withAmount(taxable, split.getNet()));
            }
        }

        return additionalItems;
    }

    /**
     * The price mode actually used, which is not always the configured one.
     *
     * Rewriting an item that already carries adjustments would silently change what those
     * adjustments were computed against, so the rewrite is off for an adjusted item. The split has
     * to switch with it: extracting VAT out of the gross while leaving the charge line gross
     * produced a tax amount matching no rate and a total matching nothing at all. Charging on top
     * is the behaviour the warning had always claimed, and now the behaviour it describes.
     */
    static PriceMode effectivePriceMode(final PriceMode configured, final boolean itemIsAdjusted) {
        return configured == PriceMode.INCLUSIVE && itemIsAdjusted ? PriceMode.EXCLUSIVE : configured;
    }

    private VatTreatmentRequest request(final CustomerProfile customer,
                                        final InvoiceItem taxable,
                                        final Invoice invoice) {
        return VatTreatmentRequest.builder()
                                  .customerCountry(customer.getTaxCountry())
                                  .customerVatNumber(customer.getVatNumber())
                                  .customerVatNumberValidated(customer.isVatNumberValidated())
                                  .planName(taxable.getPlanName())
                                  .productName(taxable.getProductName())
                                  .taxPoint(taxPoint(taxable, invoice))
                                  .build();
    }

    /**
     * Time of supply: the start of the service period when there is one, otherwise the invoice
     * date. Rates are looked up as at this date so that a credit against an old supply reverses
     * VAT at the rate that applied then.
     */
    private static LocalDate taxPoint(final InvoiceItem item, final Invoice invoice) {
        final org.joda.time.LocalDate joda = item.getStartDate() != null
                                             ? item.getStartDate()
                                             : invoice.getInvoiceDate();
        if (joda == null) {
            return null;
        }
        return LocalDate.of(joda.getYear(), joda.getMonthOfYear(), joda.getDayOfMonth());
    }

    /**
     * The same invoice item with a different amount, and crucially the same id.
     *
     * Kill Bill's {@code InvoicePluginDispatcher} matches returned items against the invoice by
     * id. An item whose id already exists is treated as an update rather than an addition, and
     * {@code amount} is one of the fields a plugin is allowed to change. That is what makes
     * VAT-inclusive pricing possible: the gross charge becomes a net charge and the TAX item
     * makes up the difference, so the invoice total is exactly what the customer was quoted.
     *
     * Fields Kill Bill treats as immutable (type, dates, currency, linkedItemId, catalogue names)
     * are echoed back unchanged so the update produces no warnings.
     */
    static InvoiceItem withAmount(final InvoiceItem item, final BigDecimal amount) {
        return new PluginInvoiceItem(item.getId(),
                                     item.getInvoiceItemType(),
                                     item.getInvoiceId(),
                                     item.getAccountId(),
                                     item.getChildAccountId(),
                                     item.getStartDate(),
                                     item.getEndDate(),
                                     amount,
                                     item.getCurrency(),
                                     item.getDescription(),
                                     item.getSubscriptionId(),
                                     item.getBundleId(),
                                     item.getCatalogEffectiveDate(),
                                     item.getProductName(),
                                     item.getPrettyProductName(),
                                     item.getPlanName(),
                                     item.getPrettyPlanName(),
                                     item.getPhaseName(),
                                     item.getPrettyPhaseName(),
                                     item.getRate(),
                                     item.getLinkedItemId(),
                                     item.getUsageName(),
                                     item.getPrettyUsageName(),
                                     item.getQuantity(),
                                     item.getItemDetails(),
                                     item.getCreatedDate(),
                                     item.getUpdatedDate());
    }
}
