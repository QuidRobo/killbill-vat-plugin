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
 * Adds the arithmetic a VAT invoice needs and Mustache cannot do on its own.
 *
 * Mustache is logic-less, so the stock template can print every line and the gross total but
 * cannot produce "subtotal excluding VAT / VAT / total including VAT", nor a per-line VAT rate.
 * Everything computed here is exposed as a plain getter, which JMustache resolves by reflection,
 * so {@code getNetTotal()} becomes <code>{{invoice.netTotal}}</code> with no registration step.
 *
 * Tax detection is structural and engine agnostic: any {@link InvoiceItemType#TAX} item counts,
 * whether it came from the AvaTax plugin or from this project's VAT plugin.
 */
public class VatInvoiceFormatter extends DefaultInvoiceFormatter {

    private static final Logger logger = LoggerFactory.getLogger(VatInvoiceFormatter.class);

    /** Account custom field holding the customer's VAT registration number. */
    public static final String CUSTOMER_VAT_CUSTOM_FIELD = "customerVatNumber";

    /**
     * Account custom field overriding the country used for the VAT decision.
     *
     * The calculator honours this field, so the formatter has to honour it too. Without that, a
     * customer whose billing address is in one country and whose place of supply is another gets
     * VAT charged on one basis and a legend printed on the other.
     */
    public static final String CUSTOMER_TAX_COUNTRY_CUSTOM_FIELD = "customerTaxCountry";

    /** @deprecated use {@link FormatterSettings#TENANT_ID_PROPERTY}. Kept so existing deployments do not break. */
    @Deprecated
    public static final String TENANT_ID_PROPERTY = FormatterSettings.TENANT_ID_PROPERTY;

    // DefaultInvoiceFormatter keeps every field private, so a subclass has to hold its own copies.
    private final String defaultLocale;
    private final String catalogBundlePath;
    private final Invoice invoice;
    private final Locale locale;
    private final ResourceBundle bundle;
    private final ResourceBundle defaultBundle;
    private final DateTimeFormatter dateFormatter;
    private final OSGIKillbillAPI killbillAPI;
    private final FormatterSettings settings;

    /** Items that make up the taxable base: charges, plus the adjustments that reduce them. */
    private final List<InvoiceItem> chargeItems = new ArrayList<InvoiceItem>();
    /** Credits and account balance movements. Not part of the taxable base. */
    private final List<InvoiceItem> creditItems = new ArrayList<InvoiceItem>();
    private final List<InvoiceItem> taxItems = new ArrayList<InvoiceItem>();

    private final Map<UUID, BigDecimal> vatByChargeItemId = new LinkedHashMap<UUID, BigDecimal>();
    private final List<VatBand> vatBreakdown = new ArrayList<VatBand>();

    private final BigDecimal netTotal;
    private final BigDecimal vatTotal;
    private final BigDecimal creditTotal;
    private final String customerVatNumber;
    private final String customerCountry;
    private final boolean vatContextAvailable;

    public VatInvoiceFormatter(final String defaultLocale,
                               final String catalogBundlePath,
                               final Invoice invoice,
                               final Locale locale,
                               final CurrencyConversionApi currencyConversionApi,
                               final ResourceBundle bundle,
                               final ResourceBundle defaultBundle,
                               final OSGIKillbillAPI killbillAPI,
                               final FormatterSettings settings) {
        super(defaultLocale, catalogBundlePath, invoice, locale, currencyConversionApi, bundle, defaultBundle);
        this.defaultLocale = defaultLocale;
        this.catalogBundlePath = catalogBundlePath;
        this.invoice = invoice;
        this.locale = locale;
        this.bundle = bundle;
        this.defaultBundle = defaultBundle;
        this.dateFormatter = DateTimeFormat.mediumDate().withLocale(locale);
        this.killbillAPI = killbillAPI;
        this.settings = settings;

        // Use the superclass' merged view so CBA and credit adjustments behave as they normally do.
        for (final InvoiceItem item : super.mergeCBAAndCreditAdjustmentItems()) {
            final InvoiceItemType type = item.getInvoiceItemType();
            if (InvoiceItemType.TAX.equals(type)) {
                taxItems.add(item);
            } else if (isCredit(type)) {
                creditItems.add(item);
            } else {
                chargeItems.add(item);
            }
        }

        this.netTotal = sum(chargeItems);
        this.creditTotal = sum(creditItems);

        BigDecimal vat = BigDecimal.ZERO;
        for (final InvoiceItem tax : taxItems) {
            vat = vat.add(safe(tax.getAmount()));
            final UUID linked = tax.getLinkedItemId();
            if (linked != null) {
                final BigDecimal running = vatByChargeItemId.get(linked);
                vatByChargeItemId.put(linked,
                                      running == null ? safe(tax.getAmount())
                                                      : running.add(safe(tax.getAmount())));
            }
        }
        this.vatTotal = vat;

        buildVatBreakdown();

        final CustomerFacts facts = lookupCustomerFacts();
        this.vatContextAvailable = facts.available;
        this.customerVatNumber = facts.vatNumber;
        this.customerCountry = facts.country;
    }

    /**
     * A credit is not a supply, so it never belongs in the taxable base.
     *
     * {@code CBA_ADJ} moves money to or from the account balance and {@code CREDIT_ADJ} is a
     * goodwill credit. Neither is VAT-bearing. {@code ITEM_ADJ} and {@code REPAIR_ADJ} are
     * different: they reduce the value of an actual supply, so they stay in the base.
     */
    private static boolean isCredit(final InvoiceItemType type) {
        return InvoiceItemType.CBA_ADJ.equals(type) || InvoiceItemType.CREDIT_ADJ.equals(type);
    }

    // ------------------------------------------------------------------
    // Totals
    //
    // These three are self consistent by construction: net + VAT = grand total. Kill Bill's own
    // getChargedAmount() answers a different question (what the account owes, credits included),
    // so a template that prints it as "total including VAT" will not add up on any invoice that
    // carries a credit. Print getFormattedGrandTotal() as the VAT invoice total and show the
    // credit on its own row.
    // ------------------------------------------------------------------

    /** Sum of every charge and charge adjustment: the taxable base, excluding credits. */
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

    /** Net plus VAT: the value of the supplies on this invoice, before any credit is applied. */
    public BigDecimal getGrandTotal() {
        return netTotal.add(vatTotal);
    }

    public String getFormattedGrandTotal() {
        return money(getGrandTotal());
    }

    /** Credits applied to this invoice, normally negative. */
    public BigDecimal getCreditTotal() {
        return creditTotal;
    }

    public String getFormattedCreditTotal() {
        return money(creditTotal);
    }

    /** Lets the template hide the credit row on the overwhelming majority of invoices. */
    public boolean getAnyCredit() {
        return creditTotal.compareTo(BigDecimal.ZERO) != 0;
    }

    /**
     * The VAT summary table: one row per distinct tax description, with the net base it was
     * charged on and the effective rate.
     */
    public List<VatBand> getVatBreakdown() {
        return vatBreakdown;
    }

    // ------------------------------------------------------------------
    // VAT treatment
    //
    // At most one of these is true. All four are false when the treatment cannot be established,
    // which is what happens if no tenant id is configured: see getVatContextAvailable().
    // ------------------------------------------------------------------

    /** VAT was actually charged. Only then is this document a VAT invoice. */
    public boolean getStandardVat() {
        return vatTotal.compareTo(BigDecimal.ZERO) != 0;
    }

    /**
     * VAT charged at the supplier's own country's rate: an ordinary domestic sale.
     *
     * Split out from {@link #getDestinationVat()} because the two are different supplies that a
     * VAT invoice has to describe differently, even though both simply "charge VAT". A UK
     * supplier selling to a UK customer charges UK VAT and accounts for it on a UK return.
     */
    public boolean getDomesticVat() {
        return getStandardVat() && vatContextAvailable && !isOverseas();
    }

    /**
     * VAT charged at the CUSTOMER's country's rate: an EU B2C supply under the One Stop Shop, or
     * a country where the supplier holds a local registration.
     *
     * This is the case that makes an invoice template EU-capable rather than UK-only. The money
     * collected is German, Spanish or Italian VAT, remitted through OSS or a local registration,
     * not the supplier's domestic VAT. An invoice that presents it as UK VAT misstates which
     * authority is owed the money, and the customer's own accountant needs to see which country's
     * rate was applied.
     */
    public boolean getDestinationVat() {
        return getStandardVat() && vatContextAvailable && isOverseas();
    }

    /** Zero VAT to a VAT-registered customer abroad: the recipient accounts for the VAT. */
    public boolean getReverseCharge() {
        return vatContextAvailable && !getStandardVat() && isOverseas()
               && customerVatNumber != null && !customerVatNumber.trim().isEmpty();
    }

    /** Zero VAT to a customer abroad with no VAT number on file: place of supply is elsewhere. */
    public boolean getOutsideScope() {
        return vatContextAvailable && !getStandardVat() && isOverseas()
               && (customerVatNumber == null || customerVatNumber.trim().isEmpty());
    }

    /** Zero VAT to a domestic customer, for example a fully credited or zero-value invoice. */
    public boolean getZeroRated() {
        return vatContextAvailable && !getStandardVat() && !isOverseas();
    }

    /**
     * True when no VAT was charged and the formatter could not establish why.
     *
     * This is not a hypothetical: without {@link FormatterSettings#TENANT_ID_PROPERTY} set, the
     * formatter cannot read the account at all, so it knows neither the customer's country nor
     * their VAT number. Guessing at that point would print a specific legal statement, most often
     * a domestic zero-rating notice on what is really a reverse charge invoice. The template
     * should print a neutral "no VAT has been charged" note against this flag, and the deployment
     * should be fixed by configuring the tenant id.
     */
    public boolean getVatTreatmentUnknown() {
        return !getStandardVat() && !vatContextAvailable;
    }

    /** False when the account could not be read, which makes every treatment flag unreliable. */
    public boolean getVatContextAvailable() {
        return vatContextAvailable;
    }

    public String getCustomerVatNumber() {
        return customerVatNumber;
    }

    public String getCustomerCountry() {
        return customerCountry;
    }

    /** Country the supplier is established in, as configured. */
    public String getSupplierCountry() {
        return settings.getSupplierCountry();
    }

    private boolean isOverseas() {
        return customerCountry != null
               && !settings.getSupplierCountry().equalsIgnoreCase(customerCountry.trim());
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
        final Map<String, Map<UUID, BigDecimal>> netByDescription =
                new LinkedHashMap<String, Map<UUID, BigDecimal>>();

        for (final InvoiceItem tax : taxItems) {
            final String description = (tax.getDescription() == null || tax.getDescription().trim().isEmpty())
                                       ? "VAT" : tax.getDescription().trim();

            final BigDecimal running = vatByDescription.get(description);
            vatByDescription.put(description,
                                 running == null ? safe(tax.getAmount())
                                                 : running.add(safe(tax.getAmount())));

            Map<UUID, BigDecimal> bases = netByDescription.get(description);
            if (bases == null) {
                bases = new LinkedHashMap<UUID, BigDecimal>();
                netByDescription.put(description, bases);
            }
            final UUID linked = tax.getLinkedItemId();
            if (linked != null) {
                // Keyed by linked item id so two jurisdictions on the same charge do not double
                // count the base.
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
            vatBreakdown.add(new VatBand(entry.getKey(), base, entry.getValue(),
                                         invoice.getCurrency(), locale));
        }
    }

    /**
     * The taxable value of one charge: the charge itself plus every adjustment linked to it.
     *
     * A tax engine that adjusts VAT when a charge is adjusted computes tax on the adjusted value,
     * so the base shown in the VAT summary has to be the adjusted value too. Using the raw charge
     * amount makes the derived rate on a partly credited line read as something like 17.4%.
     */
    private BigDecimal netAmountOf(final UUID itemId) {
        BigDecimal base = BigDecimal.ZERO;
        for (final InvoiceItem item : chargeItems) {
            if (itemId.equals(item.getId())
                || (itemId.equals(item.getLinkedItemId()) && isChargeAdjustment(item))) {
                base = base.add(safe(item.getAmount()));
            }
        }
        return base;
    }

    private static boolean isChargeAdjustment(final InvoiceItem item) {
        return InvoiceItemType.ITEM_ADJ.equals(item.getInvoiceItemType())
               || InvoiceItemType.REPAIR_ADJ.equals(item.getInvoiceItemType());
    }

    /**
     * Reads the customer's VAT number and country of supply from the account.
     *
     * On Kill Bill 0.24.x the {@code InvoiceFormatterFactory} signature carries no
     * {@code TenantContext}, so the tenant id has to be configured: see {@link FormatterSettings}.
     * When it is missing, or the lookup fails, this reports {@code available = false} and every
     * treatment legend is suppressed rather than guessed.
     */
    private CustomerFacts lookupCustomerFacts() {
        final TenantContext context = tenantContext();
        if (context == null) {
            logger.warn("{} is not set, so invoice {} cannot be rendered with a VAT treatment"
                        + " legend or a customer VAT number. Set it in killbill.properties.",
                        FormatterSettings.TENANT_ID_PROPERTY, invoice.getId());
            return CustomerFacts.unavailable();
        }

        String vatNumber = null;
        String taxCountryOverride = null;
        try {
            final List<CustomField> fields = killbillAPI.getCustomFieldUserApi()
                    .getCustomFieldsForObject(invoice.getAccountId(), ObjectType.ACCOUNT, context);
            if (fields != null) {
                for (final CustomField field : fields) {
                    if (CUSTOMER_VAT_CUSTOM_FIELD.equalsIgnoreCase(field.getFieldName())) {
                        vatNumber = field.getFieldValue();
                    } else if (CUSTOMER_TAX_COUNTRY_CUSTOM_FIELD.equalsIgnoreCase(field.getFieldName())) {
                        taxCountryOverride = field.getFieldValue();
                    }
                }
            }
        } catch (final RuntimeException e) {
            logger.warn("Unable to read VAT custom fields for account {}", invoice.getAccountId(), e);
            return CustomerFacts.unavailable();
        }

        String country = taxCountryOverride;
        if (country == null || country.trim().isEmpty()) {
            try {
                country = killbillAPI.getAccountUserApi()
                                     .getAccountById(invoice.getAccountId(), context).getCountry();
            } catch (final Exception e) {
                logger.warn("Unable to load account {} to determine the country of supply",
                            invoice.getAccountId(), e);
                return CustomerFacts.unavailable();
            }
        }

        if (country == null || country.trim().isEmpty()) {
            // The account exists but has no country. Nothing can be said about place of supply.
            logger.warn("Account {} has no country set, so invoice {} cannot carry a VAT treatment"
                        + " legend", invoice.getAccountId(), invoice.getId());
            return CustomerFacts.unavailable();
        }
        return new CustomerFacts(true, vatNumber, country.trim().toUpperCase());
    }

    private TenantContext tenantContext() {
        if (killbillAPI == null || settings.getTenantId() == null) {
            return null;
        }
        return new PluginTenantContext(invoice.getAccountId(), settings.getTenantId());
    }

    private static BigDecimal sum(final List<InvoiceItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (final InvoiceItem item : items) {
            total = total.add(safe(item.getAmount()));
        }
        return total;
    }

    static BigDecimal safe(final BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    /** What could be established about the customer, and whether anything could be at all. */
    private static final class CustomerFacts {

        private final boolean available;
        private final String vatNumber;
        private final String country;

        private CustomerFacts(final boolean available, final String vatNumber, final String country) {
            this.available = available;
            this.vatNumber = vatNumber;
            this.country = country;
        }

        static CustomerFacts unavailable() {
            return new CustomerFacts(false, null, null);
        }
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
