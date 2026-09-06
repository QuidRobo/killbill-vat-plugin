/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.formatter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;

import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.killbill.billing.ObjectType;
import org.killbill.billing.currency.api.CurrencyConversionApi;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.invoice.template.formatters.DefaultInvoiceFormatter;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.plugin.api.PluginTenantContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.customfield.CustomField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds the arithmetic a UK VAT invoice needs and Mustache cannot do on its own.
 *
 * Mustache is logic-less, so the stock template can print every line and the gross total but
 * cannot produce "subtotal excluding VAT / VAT / total including VAT", nor a per-line VAT rate.
 * Everything computed here is exposed as a plain getter, which JMustache resolves by reflection,
 * so {@code getNetTotal()} becomes <code>{{invoice.netTotal}}</code> with no registration step.
 *
 * Tax detection is structural and engine agnostic: any {@link InvoiceItemType#TAX} item counts,
 * whether it came from the AvaTax plugin or from an in-house VAT plugin.
 */
public class VatInvoiceFormatter extends DefaultInvoiceFormatter {

    private static final Logger logger = LoggerFactory.getLogger(VatInvoiceFormatter.class);

    /** Account custom field holding the customer's VAT registration number. */
    public static final String CUSTOMER_VAT_CUSTOM_FIELD = "customerVatNumber";

    /** System property carrying the Kill Bill tenant id, needed to read custom fields on 0.24.x. */
    public static final String TENANT_ID_PROPERTY = "org.killbill.billing.plugin.vat.formatter.tenantId";

    private static final String HOME_COUNTRY = "GB";

    // DefaultInvoiceFormatter keeps every field private, so a subclass has to hold its own copies.
    private final String defaultLocale;
    private final String catalogBundlePath;
    private final Invoice invoice;
    private final Locale locale;
    private final ResourceBundle bundle;
    private final ResourceBundle defaultBundle;
    private final DateTimeFormatter dateFormatter;
    private final OSGIKillbillAPI killbillAPI;

    private final List<InvoiceItem> chargeItems = new ArrayList<InvoiceItem>();
    private final List<InvoiceItem> taxItems = new ArrayList<InvoiceItem>();
    private final Map<UUID, BigDecimal> vatByChargeItemId = new LinkedHashMap<UUID, BigDecimal>();
    private final List<VatBand> vatBreakdown = new ArrayList<VatBand>();

    private final BigDecimal netTotal;
    private final BigDecimal vatTotal;
    private final String customerVatNumber;
    private final String customerCountry;

    public VatInvoiceFormatter(final String defaultLocale,
                                 final String catalogBundlePath,
                                 final Invoice invoice,
                                 final Locale locale,
                                 final CurrencyConversionApi currencyConversionApi,
                                 final ResourceBundle bundle,
                                 final ResourceBundle defaultBundle,
                                 final OSGIKillbillAPI killbillAPI) {
        super(defaultLocale, catalogBundlePath, invoice, locale, currencyConversionApi, bundle, defaultBundle);
        this.defaultLocale = defaultLocale;
        this.catalogBundlePath = catalogBundlePath;
        this.invoice = invoice;
        this.locale = locale;
        this.bundle = bundle;
        this.defaultBundle = defaultBundle;
        this.dateFormatter = DateTimeFormat.mediumDate().withLocale(locale);
        this.killbillAPI = killbillAPI;

        // Use the superclass' merged view so CBA and credit adjustments behave as they normally do.
        for (final InvoiceItem item : super.mergeCBAAndCreditAdjustmentItems()) {
            if (InvoiceItemType.TAX.equals(item.getInvoiceItemType())) {
                taxItems.add(item);
            } else {
                chargeItems.add(item);
            }
        }

        BigDecimal net = BigDecimal.ZERO;
        for (final InvoiceItem item : chargeItems) {
            net = net.add(safe(item.getAmount()));
        }
        this.netTotal = net;

        BigDecimal vat = BigDecimal.ZERO;
        for (final InvoiceItem tax : taxItems) {
            vat = vat.add(safe(tax.getAmount()));
            final UUID linked = tax.getLinkedItemId();
            if (linked != null) {
                final BigDecimal running = vatByChargeItemId.get(linked);
                vatByChargeItemId.put(linked, running == null ? safe(tax.getAmount()) : running.add(safe(tax.getAmount())));
            }
        }
        this.vatTotal = vat;

        buildVatBreakdown();

        this.customerVatNumber = lookupCustomerVatNumber();
        this.customerCountry = lookupCustomerCountry();
    }

    // ------------------------------------------------------------------
    // Totals
    // ------------------------------------------------------------------

    /** Sum of every non-tax item. Catalogue prices are VAT exclusive, so this is the taxable base. */
    public BigDecimal getNetTotal() {
        return netTotal;
    }

    public String getFormattedNetTotal() {
        return money(netTotal);
    }

    /** Sum of every TAX item on the invoice. */
    public BigDecimal getVatTotal() {
        return vatTotal;
    }

    public String getFormattedVatTotal() {
        return money(vatTotal);
    }

    /**
     * The VAT summary table: one row per distinct tax description, with the net base it was
     * charged on and the effective rate.
     */
    public List<VatBand> getVatBreakdown() {
        return vatBreakdown;
    }

    // ------------------------------------------------------------------
    // VAT treatment. Exactly one of these is true, so the template can pick a legend.
    // ------------------------------------------------------------------

    /** VAT was actually charged. Only then is this document a VAT invoice. */
    public boolean getStandardVat() {
        return vatTotal.compareTo(BigDecimal.ZERO) != 0;
    }

    /** Zero VAT to a VAT-registered customer outside the UK: the recipient accounts for the VAT. */
    public boolean getReverseCharge() {
        return !getStandardVat() && isOverseas() && customerVatNumber != null && !customerVatNumber.trim().isEmpty();
    }

    /** Zero VAT to an overseas customer with no VAT number on file: place of supply is outside the UK. */
    public boolean getOutsideScope() {
        return !getStandardVat() && isOverseas() && (customerVatNumber == null || customerVatNumber.trim().isEmpty());
    }

    /** Zero VAT to a UK customer, for example a fully credited or zero-value invoice. */
    public boolean getZeroRated() {
        return !getStandardVat() && !isOverseas();
    }

    public String getCustomerVatNumber() {
        return customerVatNumber;
    }

    public String getCustomerCountry() {
        return customerCountry;
    }

    private boolean isOverseas() {
        return customerCountry != null && !HOME_COUNTRY.equalsIgnoreCase(customerCountry.trim());
    }

    // ------------------------------------------------------------------
    // Dates and presentation helpers
    // ------------------------------------------------------------------

    /**
     * Tax point (time of supply). Earliest service period start across the charges, falling back
     * to the invoice date when nothing on the invoice carries a service period.
     */
    public String getFormattedTaxPointDate() {
        LocalDate earliest = null;
        for (final InvoiceItem item : chargeItems) {
            final LocalDate start = item.getStartDate();
            if (start != null && (earliest == null || start.isBefore(earliest))) {
                earliest = start;
            }
        }
        final LocalDate taxPoint = earliest != null ? earliest : invoice.getInvoiceDate();
        return taxPoint == null ? "" : taxPoint.toString(dateFormatter);
    }

    /** True when anything has been paid, so the template can hide an all-zero "Paid" row. */
    public boolean getAnyPayment() {
        return safe(invoice.getPaidAmount()).compareTo(BigDecimal.ZERO) != 0;
    }

    /** True when the payment currency differs from the invoice currency. */
    public boolean getMultiCurrency() {
        return getProcessedCurrency() != null && !getProcessedCurrency().equals(invoice.getCurrency());
    }

    // ------------------------------------------------------------------
    // Item lists
    // ------------------------------------------------------------------

    /**
     * The charge lines only. Tax is shown as a rate column and in the VAT summary rather than as
     * its own row, which is what a VAT invoice is supposed to look like.
     */
    public List<InvoiceItem> getLineItems() {
        final List<InvoiceItem> formatted = new ArrayList<InvoiceItem>();
        for (final InvoiceItem item : chargeItems) {
            formatted.add(wrap(item));
        }
        return formatted;
    }

    /** The raw TAX items, kept available for debugging and for any template that wants them inline. */
    public List<InvoiceItem> getTaxItems() {
        final List<InvoiceItem> formatted = new ArrayList<InvoiceItem>();
        for (final InvoiceItem item : taxItems) {
            formatted.add(wrap(item));
        }
        return formatted;
    }

    /** Every item, tax included, so the stock Kill Bill template keeps working against this formatter. */
    @Override
    public List<InvoiceItem> getInvoiceItems() {
        final List<InvoiceItem> formatted = new ArrayList<InvoiceItem>();
        for (final InvoiceItem item : super.mergeCBAAndCreditAdjustmentItems()) {
            formatted.add(wrap(item));
        }
        return formatted;
    }

    private VatInvoiceItemFormatter wrap(final InvoiceItem item) {
        final BigDecimal itemVat = vatByChargeItemId.get(item.getId());
        return new VatInvoiceItemFormatter(defaultLocale, catalogBundlePath, item, dateFormatter,
                                             locale, bundle, defaultBundle,
                                             itemVat == null ? BigDecimal.ZERO : itemVat,
                                             invoice.getCurrency());
    }

    // ------------------------------------------------------------------
    // Consistent money formatting
    //
    // The superclass formats invoice totals through joda-money while DefaultInvoiceItemFormatter
    // uses a plain NumberFormat, so the two can disagree. Overriding all of them with one method
    // keeps every figure on the page rendered identically.
    // ------------------------------------------------------------------

    @Override
    public String getFormattedChargedAmount() {
        return money(getChargedAmount());
    }

    @Override
    public String getFormattedPaidAmount() {
        return money(getPaidAmount());
    }

    @Override
    public String getFormattedBalance() {
        return money(getBalance());
    }

    private String money(final BigDecimal amount) {
        return VatMoney.format(safe(amount), invoice.getCurrency(), locale);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void buildVatBreakdown() {
        // Group tax by description. AvaTax emits one TAX item per jurisdiction with the tax name
        // as the description, so grouping collapses those back into one row per rate.
        final Map<String, BigDecimal> vatByDescription = new LinkedHashMap<String, BigDecimal>();
        final Map<String, Map<UUID, BigDecimal>> netByDescription = new LinkedHashMap<String, Map<UUID, BigDecimal>>();

        for (final InvoiceItem tax : taxItems) {
            final String description = (tax.getDescription() == null || tax.getDescription().trim().isEmpty())
                                       ? "VAT" : tax.getDescription().trim();

            final BigDecimal running = vatByDescription.get(description);
            vatByDescription.put(description, running == null ? safe(tax.getAmount()) : running.add(safe(tax.getAmount())));

            Map<UUID, BigDecimal> bases = netByDescription.get(description);
            if (bases == null) {
                bases = new LinkedHashMap<UUID, BigDecimal>();
                netByDescription.put(description, bases);
            }
            final UUID linked = tax.getLinkedItemId();
            if (linked != null) {
                // Keyed by linked item id so two jurisdictions on the same charge do not double count the base.
                bases.put(linked, netAmountOf(linked));
            }
        }

        for (final Map.Entry<String, BigDecimal> entry : vatByDescription.entrySet()) {
            BigDecimal base = BigDecimal.ZERO;
            final Map<UUID, BigDecimal> bases = netByDescription.get(entry.getKey());
            if (bases != null) {
                for (final BigDecimal amount : bases.values()) {
                    base = base.add(safe(amount));
                }
            }
            vatBreakdown.add(new VatBand(entry.getKey(), base, entry.getValue(), invoice.getCurrency(), locale));
        }
    }

    private BigDecimal netAmountOf(final UUID itemId) {
        for (final InvoiceItem item : chargeItems) {
            if (itemId.equals(item.getId())) {
                return safe(item.getAmount());
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Reads the customer's VAT registration number from an account custom field.
     *
     * The 0.24.x InvoiceFormatterFactory signature carries no TenantContext, so the tenant id has
     * to come from configuration. Set {@link #TENANT_ID_PROPERTY} for single-tenant installs. When
     * it is absent this returns null and the invoice simply omits the customer VAT number, which
     * downgrades a reverse-charge invoice to an outside-scope one rather than rendering wrong.
     */
    private String lookupCustomerVatNumber() {
        final TenantContext context = tenantContext();
        if (context == null) {
            return null;
        }
        try {
            final List<CustomField> fields = killbillAPI.getCustomFieldUserApi()
                    .getCustomFieldsForObject(invoice.getAccountId(), ObjectType.ACCOUNT, context);
            if (fields != null) {
                for (final CustomField field : fields) {
                    if (CUSTOMER_VAT_CUSTOM_FIELD.equalsIgnoreCase(field.getFieldName())) {
                        return field.getFieldValue();
                    }
                }
            }
        } catch (final RuntimeException e) {
            logger.warn("Unable to read the {} custom field for account {}",
                        CUSTOMER_VAT_CUSTOM_FIELD, invoice.getAccountId(), e);
        }
        return null;
    }

    private String lookupCustomerCountry() {
        final TenantContext context = tenantContext();
        if (context == null) {
            return null;
        }
        try {
            return killbillAPI.getAccountUserApi().getAccountById(invoice.getAccountId(), context).getCountry();
        } catch (final Exception e) {
            logger.warn("Unable to load account {} to determine the country of supply", invoice.getAccountId(), e);
            return null;
        }
    }

    private TenantContext tenantContext() {
        if (killbillAPI == null) {
            return null;
        }
        final String configured = System.getProperty(TENANT_ID_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return null;
        }
        try {
            return new PluginTenantContext(invoice.getAccountId(), UUID.fromString(configured.trim()));
        } catch (final IllegalArgumentException e) {
            logger.warn("{} is not a valid UUID: {}", TENANT_ID_PROPERTY, configured);
            return null;
        }
    }

    static BigDecimal safe(final BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    /** Shared money and percentage formatting so totals, lines and the VAT summary all agree. */
    static final class VatMoney {

        private VatMoney() {
        }

        static String format(final BigDecimal amount,
                             final org.killbill.billing.catalog.api.Currency currency,
                             final Locale locale) {
            final NumberFormat number = NumberFormat.getCurrencyInstance(locale);
            try {
                number.setCurrency(java.util.Currency.getInstance(currency.toString()));
            } catch (final IllegalArgumentException e) {
                // Non ISO-4217 currency, fall back to the locale default symbol.
            }
            return number.format(amount == null ? BigDecimal.ZERO : amount);
        }

        /**
         * AvaTax discards the rate percentage: it writes the tax amount and the tax name only.
         * The rate is therefore derived from the amounts, which is exact for a flat-rate VAT.
         */
        static String ratePercent(final BigDecimal net, final BigDecimal vat) {
            if (net == null || net.compareTo(BigDecimal.ZERO) == 0) {
                return vat == null || vat.compareTo(BigDecimal.ZERO) == 0 ? "0%" : "";
            }
            final BigDecimal percent = safe(vat)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(net, 4, RoundingMode.HALF_UP)
                    .setScale(2, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            return percent.toPlainString() + "%";
        }
    }
}
