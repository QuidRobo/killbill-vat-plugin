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
import java.util.UUID;

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
            final PriceMode priceMode = VatComputation.effectivePriceMode(configuredMode, adjusted);
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

            // A tax item is now emitted for every taxable charge, including the ones that carry no
            // VAT. A reverse charge, an outside-scope supply and a zero-rated supply are decisions,
            // not absences, and until now the invoice recorded no trace of them: the item simply
            // was not created, so nothing downstream could say why the VAT was zero, or even that
            // the question had been asked. The zero item is what carries that record.
            //
            // It is invisible on the invoice. getLineItems() in the formatter iterates charges
            // only, and the VAT summary table is gated on a non-zero VAT total, so a zero tax item
            // adds no row and changes no figure. What it does add is itemDetails, and a second
            // benefit: the charge now counts as already taxed on a re-run, where before a
            // zero-rated charge was re-evaluated every time and could acquire VAT retrospectively
            // if the customer's VAT number changed in between.
            final InvoiceItem taxItem = taxItemFor(
                    taxable, invoice.getId(), split.getVat(), treatment,
                    config.getSupplierCountry(),
                    customer == null ? null : customer.getTaxCountry(),
                    customer == null ? null : customer.getVatNumber(),
                    config.isRecordZeroVatItems());
            if (taxItem != null) {
                additionalItems.add(taxItem);
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
    /**
     * The TAX item for one charge, carrying the VAT decision on its {@code itemDetails}.
     *
     * <p>Returns null only when there is genuinely nothing to record.
     *
     * <h2>Why this does not simply call buildTaxItem</h2>
     *
     * {@code PluginTaxCalculator.buildTaxItem} opens with
     * {@code if (amount == null || ZERO.compareTo(amount) == 0) return null;}. It is built on the
     * assumption that a tax item exists to carry money, so a zero one is pointless. That is true
     * right up until the item is also the only place the invoice records WHY no money was charged,
     * which is exactly what the formatter needs and cannot look up for itself on Kill Bill 0.24.x.
     *
     * <p>So the zero case goes to {@code PluginInvoiceItem.createTaxItem} directly, which has no
     * such check. The paying case still goes through {@code buildTaxItem} so that nothing about
     * existing behaviour depends on this method getting the framework's own construction right.
     *
     * <p>Package-private and static so it can be tested. The zero path is new, and the last version
     * of it was silently dead for precisely this reason: every test covered the pieces around it
     * and none covered the call itself.
     */
    static InvoiceItem taxItemFor(final InvoiceItem taxable,
                                  final UUID invoiceId,
                                  final BigDecimal vat,
                                  final VatTreatment treatment,
                                  final String supplierCountry,
                                  final String customerCountry,
                                  final String customerVatNumber,
                                  final boolean recordZeroVatItems) {
        if (vat == null) {
            return null;
        }
        final boolean carriesVat = vat.compareTo(BigDecimal.ZERO) != 0;
        if (!carriesVat && !recordZeroVatItems) {
            return null;
        }

        // No zero check on this one, unlike PluginTaxCalculator.buildTaxItem, which is the whole
        // reason it is called directly.
        final InvoiceItem built =
                PluginInvoiceItem.createTaxItem(taxable, invoiceId, vat, description(treatment));
        if (built == null) {
            return null;
        }
        return withItemDetails(built, VatItemDetails.write(treatment, supplierCountry,
                                                           customerCountry, customerVatNumber));
    }

    /**
     * A reverse charge or outside-scope treatment carries no description, because until now it
     * never produced an item that needed one. "VAT" rather than the framework's "Tax" keeps the
     * wording consistent with the charging case on any template that does render tax lines.
     */
    private static String description(final VatTreatment treatment) {
        final String described = treatment == null ? null : treatment.getDescription();
        return described == null || described.trim().isEmpty() ? "VAT" : described;
    }

    /**
     * The same item with {@code itemDetails} attached.
     *
     * <p>{@code PluginTaxCalculator.buildTaxItem} predates this field and offers no way to set it,
     * so the built item is copied through the full constructor with the details filled in. Kill
     * Bill's {@code InvoiceItemModelDao(InvoiceItem)} copies {@code getItemDetails()} verbatim and
     * {@code invoice_items.item_details} persists it, so what is written here is what the formatter
     * reads back later.
     */
    static InvoiceItem withItemDetails(final InvoiceItem item, final String itemDetails) {
        if (itemDetails == null || itemDetails.isEmpty()) {
            return item;
        }
        return copy(item, item.getAmount(), itemDetails);
    }

    static InvoiceItem withAmount(final InvoiceItem item, final BigDecimal amount) {
        return copy(item, amount, item.getItemDetails());
    }

    private static InvoiceItem copy(final InvoiceItem item,
                                    final BigDecimal amount,
                                    final String itemDetails) {
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
                                     itemDetails,
                                     item.getCreatedDate(),
                                     item.getUpdatedDate());
    }
}
