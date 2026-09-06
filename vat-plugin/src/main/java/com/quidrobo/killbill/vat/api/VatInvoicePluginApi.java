/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.api;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.killbill.billing.account.api.Account;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.invoice.plugin.api.AdditionalItemsResult;
import org.killbill.billing.invoice.plugin.api.InvoiceContext;
import org.killbill.billing.osgi.libs.killbill.OSGIConfigPropertiesService;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.plugin.api.PluginProperties;
import org.killbill.billing.plugin.api.invoice.PluginAdditionalItemsResult;
import org.killbill.billing.plugin.api.invoice.PluginInvoicePluginApi;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.quidrobo.killbill.vat.calc.VatTaxCalculator;
import com.quidrobo.killbill.vat.core.VatConfigurationHandler;
import com.quidrobo.killbill.vat.core.VatRuntime;

/**
 * The plugin's entry point into invoice generation.
 *
 * Kill Bill calls {@link #getAdditionalInvoiceItems} while an invoice is being built, and
 * whatever comes back is merged into it. Items with a new id are added; an item whose id already
 * exists on the invoice is treated as an update, which is how VAT-inclusive pricing rewrites a
 * gross charge down to net.
 */
public class VatInvoicePluginApi extends PluginInvoicePluginApi {

    private static final Logger logger = LoggerFactory.getLogger(VatInvoicePluginApi.class);

    /** Pass this as a plugin property to bypass the plugin for one call. */
    public static final String PROPERTY_SKIP = "KILLBILL_VAT_SKIP";

    private final VatConfigurationHandler configurationHandler;
    private final VatTaxCalculator calculator;

    public VatInvoicePluginApi(final VatConfigurationHandler configurationHandler,
                               final OSGIKillbillAPI killbillAPI,
                               final OSGIConfigPropertiesService configProperties,
                               final Clock clock) {
        super(killbillAPI, configProperties, clock);
        this.configurationHandler = configurationHandler;
        this.calculator = new VatTaxCalculator(killbillAPI);
    }

    @Override
    public AdditionalItemsResult getAdditionalInvoiceItems(final Invoice invoice,
                                                           final boolean dryRun,
                                                           final Iterable<PluginProperty> properties,
                                                           final InvoiceContext context) {
        if (PluginProperties.findPluginPropertyValue(PROPERTY_SKIP, properties) != null) {
            return new PluginAdditionalItemsResult();
        }

        final VatRuntime runtime = configurationHandler.getRuntime(context.getTenantId());
        if (runtime == null || !runtime.getConfig().isEnabled()) {
            return new PluginAdditionalItemsResult();
        }

        try {
            final Account account = getAccount(invoice.getAccountId(), context);
            final CustomerProfile customer = CustomerProfile.load(account, killbillAPI,
                                                                  runtime.getConfig(), context);

            final List<InvoiceItem> additionalItems =
                    calculator.compute(invoice, customer, runtime,
                                       alreadyTaxedItems(invoice, context), context);

            if (!additionalItems.isEmpty()) {
                logger.info("VAT plugin added {} item(s) to invoice {} for account {} ({})",
                            additionalItems.size(), invoice.getId(), account.getId(),
                            customer.getTaxCountry());
            }
            return new PluginAdditionalItemsResult(additionalItems, null);
        } catch (final Exception e) {
            // Returning no items is the only safe failure mode: it produces an invoice with no
            // VAT, which is visible and fixable, rather than an invoice with wrong VAT, which is
            // not. The exception is logged loudly so it does not pass unnoticed.
            logger.error("VAT computation failed for invoice {}; no tax items were added",
                         invoice.getId(), e);
            return new PluginAdditionalItemsResult();
        }
    }

    /**
     * Items across the account's invoices that already carry a TAX item, so that re-running
     * invoice generation does not tax the same charge twice.
     *
     * Each already-taxed item is reported with every adjustment currently linked to it, which
     * marks those adjustments as accounted for. That is deliberately conservative: it can leave
     * a genuine adjustment un-refunded, but it can never repeatedly refund the same one. Doing
     * this properly needs persisted plugin state recording exactly what was taxed and when, which
     * is the database milestone.
     */
    private Map<UUID, Set<UUID>> alreadyTaxedItems(final Invoice invoice, final InvoiceContext context) {
        final Map<UUID, Set<UUID>> taxed = new HashMap<UUID, Set<UUID>>();

        collectTaxed(invoice, taxed);
        final List<Invoice> existing = context.getExistingInvoices();
        if (existing != null) {
            for (final Invoice previous : existing) {
                collectTaxed(previous, taxed);
            }
        }
        return taxed;
    }

    private static void collectTaxed(final Invoice invoice, final Map<UUID, Set<UUID>> taxed) {
        if (invoice == null || invoice.getInvoiceItems() == null) {
            return;
        }
        for (final InvoiceItem item : invoice.getInvoiceItems()) {
            if (InvoiceItemType.TAX.equals(item.getInvoiceItemType()) && item.getLinkedItemId() != null) {
                if (!taxed.containsKey(item.getLinkedItemId())) {
                    taxed.put(item.getLinkedItemId(), new HashSet<UUID>());
                }
            }
        }
        for (final InvoiceItem item : invoice.getInvoiceItems()) {
            if (item.getLinkedItemId() != null
                && (InvoiceItemType.ITEM_ADJ.equals(item.getInvoiceItemType())
                    || InvoiceItemType.REPAIR_ADJ.equals(item.getInvoiceItemType()))) {
                final Set<UUID> adjustments = taxed.get(item.getLinkedItemId());
                if (adjustments != null) {
                    adjustments.add(item.getId());
                }
            }
        }
    }

}
