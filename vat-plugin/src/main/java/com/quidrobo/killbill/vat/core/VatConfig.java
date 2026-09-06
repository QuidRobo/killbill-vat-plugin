/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.core;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.quidrobo.killbill.vat.rates.VatRateTable;
import com.quidrobo.killbill.vat.resolve.VatTreatmentKind;

/**
 * Immutable snapshot of one tenant's VAT configuration.
 *
 * Built from a plain {@code .properties} payload uploaded to
 * {@code POST /1.0/kb/tenants/uploadPluginConfig/killbill-vat}, which Kill Bill re-reads on
 * {@code TENANT_CONFIG_CHANGE}, so rates and price modes change without a restart.
 *
 * See {@code docs/CONFIGURATION.md} for the full reference.
 */
public final class VatConfig {

    public static final String PROPERTY_PREFIX = "org.killbill.billing.plugin.vat.";

    // --- keys -------------------------------------------------------------
    public static final String SUPPLIER_COUNTRY = PROPERTY_PREFIX + "supplierCountry";
    public static final String PRICE_MODE = PROPERTY_PREFIX + "priceMode";
    public static final String PRICE_MODE_PLAN_PREFIX = PROPERTY_PREFIX + "priceMode.plans.";
    public static final String PRICE_MODE_PRODUCT_PREFIX = PROPERTY_PREFIX + "priceMode.products.";
    public static final String ROUNDING_SCALE = PROPERTY_PREFIX + "rounding.scale";
    public static final String ROUNDING_MODE = PROPERTY_PREFIX + "rounding.mode";
    public static final String RATES_PREFIX = PROPERTY_PREFIX;
    public static final String EU_COUNTRIES = PROPERTY_PREFIX + "euCountries";
    public static final String REVERSE_CHARGE_COUNTRIES = PROPERTY_PREFIX + "reverseChargeCountries";
    public static final String EU_B2C_TREATMENT = PROPERTY_PREFIX + "euB2cTreatment";
    public static final String EU_B2C_UNREGISTERED_TREATMENT = PROPERTY_PREFIX + "euB2cUnregisteredTreatment";
    public static final String OSS_REGISTERED = PROPERTY_PREFIX + "ossRegistered";
    public static final String UNKNOWN_COUNTRY_TREATMENT = PROPERTY_PREFIX + "unknownCountryTreatment";
    public static final String REGISTERED_COUNTRIES = PROPERTY_PREFIX + "registeredCountries";
    public static final String VAT_NUMBER_VALIDATION = PROPERTY_PREFIX + "vatNumberValidation";
    public static final String VALIDATION_MAX_AGE_DAYS = PROPERTY_PREFIX + "vatNumberValidation.maxAgeDays";
    public static final String TAX_ITEM_DESCRIPTION = PROPERTY_PREFIX + "taxItemDescription";
    public static final String REVERSE_CHARGE_LEGEND = PROPERTY_PREFIX + "legend.reverseCharge";
    public static final String OUTSIDE_SCOPE_LEGEND = PROPERTY_PREFIX + "legend.outsideScope";
    public static final String TREATMENT_RESOLVER = PROPERTY_PREFIX + "treatmentResolver";
    public static final String ENABLED = PROPERTY_PREFIX + "enabled";

    /** The 27 EU member states, used when {@link #EU_COUNTRIES} is not overridden. */
    private static final String DEFAULT_EU_COUNTRIES =
            "AT,BE,BG,HR,CY,CZ,DK,EE,FI,FR,DE,GR,HU,IE,IT,LV,LT,LU,MT,NL,PL,PT,RO,SK,SI,ES,SE";

    private static final String DEFAULT_REVERSE_CHARGE_LEGEND =
            "Reverse charge: VAT to be accounted for by the recipient.";
    private static final String DEFAULT_OUTSIDE_SCOPE_LEGEND =
            "Outside the scope of UK VAT: place of supply is outside the United Kingdom.";

    private final boolean enabled;
    private final String supplierCountry;
    private final PriceMode defaultPriceMode;
    private final Map<String, PriceMode> priceModeByPlan;
    private final Map<String, PriceMode> priceModeByProduct;
    private final int roundingScale;
    private final RoundingMode roundingMode;
    private final Set<String> euCountries;
    private final Set<String> reverseChargeCountries;
    private final Set<String> registeredCountries;
    private final VatTreatmentKind euB2cTreatment;
    private final VatTreatmentKind euB2cUnregisteredTreatment;
    private final boolean ossRegistered;
    private final VatTreatmentKind unknownCountryTreatment;
    private final VatNumberValidationMode vatNumberValidation;
    private final int validationMaxAgeDays;
    private final String taxItemDescriptionTemplate;
    private final String reverseChargeLegend;
    private final String outsideScopeLegend;
    private final String treatmentResolverClass;
    private final VatRateTable rateTable;
    private final List<String> problems;

    public VatConfig(final Properties properties) {
        final Properties p = properties == null ? new Properties() : properties;

        this.enabled = !"false".equalsIgnoreCase(get(p, ENABLED, "true"));
        this.supplierCountry = get(p, SUPPLIER_COUNTRY, "GB").trim().toUpperCase();
        this.defaultPriceMode = PriceMode.parse(get(p, PRICE_MODE, null), PriceMode.EXCLUSIVE);
        this.priceModeByPlan = readPriceModeOverrides(p, PRICE_MODE_PLAN_PREFIX);
        this.priceModeByProduct = readPriceModeOverrides(p, PRICE_MODE_PRODUCT_PREFIX);
        this.roundingScale = readInt(get(p, ROUNDING_SCALE, "2"), 2);
        this.roundingMode = readRoundingMode(get(p, ROUNDING_MODE, "HALF_UP"));
        this.euCountries = readCountrySet(get(p, EU_COUNTRIES, DEFAULT_EU_COUNTRIES));
        // Defaults to the EU list: for a UK supplier the reverse charge wording is an EU concept,
        // and a non-EU B2B supply is outside the scope of UK VAT instead. An EU-established
        // supplier should add GB here.
        this.reverseChargeCountries = readCountrySet(
                get(p, REVERSE_CHARGE_COUNTRIES, get(p, EU_COUNTRIES, DEFAULT_EU_COUNTRIES)));
        this.registeredCountries = readCountrySet(get(p, REGISTERED_COUNTRIES, ""));
        this.euB2cTreatment = readTreatment(get(p, EU_B2C_TREATMENT, "DESTINATION"), VatTreatmentKind.DESTINATION);
        this.euB2cUnregisteredTreatment = readTreatment(get(p, EU_B2C_UNREGISTERED_TREATMENT, "DOMESTIC"),
                                                        VatTreatmentKind.DOMESTIC);
        // Defaults to false. Holding a rate for a country is not authority to charge it.
        this.ossRegistered = "true".equalsIgnoreCase(get(p, OSS_REGISTERED, "false"));
        this.unknownCountryTreatment = readTreatment(get(p, UNKNOWN_COUNTRY_TREATMENT, "DOMESTIC"),
                                                     VatTreatmentKind.DOMESTIC);
        this.vatNumberValidation = VatNumberValidationMode.parse(get(p, VAT_NUMBER_VALIDATION, null));
        this.validationMaxAgeDays = readInt(get(p, VALIDATION_MAX_AGE_DAYS, "90"), 90);
        this.taxItemDescriptionTemplate = get(p, TAX_ITEM_DESCRIPTION, "VAT {rate}");
        this.reverseChargeLegend = get(p, REVERSE_CHARGE_LEGEND, DEFAULT_REVERSE_CHARGE_LEGEND);
        this.outsideScopeLegend = get(p, OUTSIDE_SCOPE_LEGEND, DEFAULT_OUTSIDE_SCOPE_LEGEND);
        this.treatmentResolverClass = get(p, TREATMENT_RESOLVER, null);
        this.rateTable = VatRateTable.fromProperties(p, RATES_PREFIX);
        this.problems = Collections.unmodifiableList(findProblems(p));
    }

    // --- accessors --------------------------------------------------------

    /** Master switch. When false the plugin returns no items at all. */
    public boolean isEnabled() {
        return enabled;
    }

    public String getSupplierCountry() {
        return supplierCountry;
    }

    /**
     * How to read the catalogue price for a given plan or product.
     *
     * Resolution order is plan, then product, then the tenant default. That ordering matters:
     * consumer plans priced inclusively can sit beside business plans priced exclusively in the
     * same catalogue, which is the common shape once a product sells both ways.
     */
    public PriceMode getPriceMode(final String planName, final String productName) {
        if (planName != null) {
            final PriceMode byPlan = priceModeByPlan.get(planName);
            if (byPlan != null) {
                return byPlan;
            }
        }
        if (productName != null) {
            final PriceMode byProduct = priceModeByProduct.get(productName);
            if (byProduct != null) {
                return byProduct;
            }
        }
        return defaultPriceMode;
    }

    public PriceMode getDefaultPriceMode() {
        return defaultPriceMode;
    }

    public int getRoundingScale() {
        return roundingScale;
    }

    public RoundingMode getRoundingMode() {
        return roundingMode;
    }

    public boolean isEuCountry(final String country) {
        return country != null && euCountries.contains(country.trim().toUpperCase());
    }

    /**
     * Countries where a B2B supply gets the reverse charge rather than being outside scope.
     *
     * Both outcomes are 0%, but they are not the same thing and the invoice must say which.
     * For a UK supplier the reverse charge applies to EU business customers, while a business
     * customer elsewhere is simply outside the scope of UK VAT.
     */
    public boolean isReverseChargeCountry(final String country) {
        return country != null && reverseChargeCountries.contains(country.trim().toUpperCase());
    }

    /** Countries the supplier holds a VAT or GST registration in, beyond its own. */
    public boolean isRegisteredIn(final String country) {
        return country != null && registeredCountries.contains(country.trim().toUpperCase());
    }

    public VatTreatmentKind getEuB2cTreatment() {
        return euB2cTreatment;
    }

    /**
     * Whether the supplier actually holds an EU One Stop Shop registration.
     *
     * This is deliberately separate from having EU rates configured. A rate in the table says what
     * Germany charges; it says nothing about whether this supplier may collect and remit it.
     * Charging German VAT without an OSS registration means collecting tax with no mechanism to
     * hand it over, which is worse than not charging it.
     */
    public boolean isOssRegistered() {
        return ossRegistered;
    }

    /** What an EU consumer gets before the OSS registration exists. */
    public VatTreatmentKind getEuB2cUnregisteredTreatment() {
        return euB2cUnregisteredTreatment;
    }

    public VatTreatmentKind getUnknownCountryTreatment() {
        return unknownCountryTreatment;
    }

    public VatNumberValidationMode getVatNumberValidation() {
        return vatNumberValidation;
    }

    public int getValidationMaxAgeDays() {
        return validationMaxAgeDays;
    }

    /** Template for the TAX item description. {@code {rate}} and {@code {country}} are substituted. */
    public String renderTaxItemDescription(final String ratePercentLabel, final String country) {
        return taxItemDescriptionTemplate
                .replace("{rate}", ratePercentLabel == null ? "" : ratePercentLabel)
                .replace("{country}", country == null ? "" : country)
                .trim();
    }

    public String getReverseChargeLegend() {
        return reverseChargeLegend;
    }

    public String getOutsideScopeLegend() {
        return outsideScopeLegend;
    }

    public String getTreatmentResolverClass() {
        return treatmentResolverClass;
    }

    public VatRateTable getRateTable() {
        return rateTable;
    }

    /**
     * Everything wrong or suspicious about this configuration, in plain English.
     *
     * A tax plugin whose configuration fails silently is worse than one that refuses to start,
     * because the failure shows up as money rather than as an error. A typo in a property key is
     * not an error to Kill Bill: it is simply a key nobody reads, and every supply quietly
     * becomes outside scope. These problems are logged at startup and served from
     * {@code GET /plugins/killbill-vat/config} so they can be seen without generating an invoice.
     */
    public List<String> getProblems() {
        return problems;
    }

    /** Recognised keys, used to catch typos in uploaded configuration. */
    private static final Set<String> KNOWN_KEYS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList("enabled", "supplierCountry", "priceMode", "rounding.scale", "rounding.mode",
                          "euCountries", "reverseChargeCountries", "euB2cTreatment",
                          "euB2cUnregisteredTreatment", "ossRegistered", "unknownCountryTreatment",
                          "registeredCountries", "vatNumberValidation", "vatNumberValidation.maxAgeDays",
                          "taxItemDescription", "legend.reverseCharge", "legend.outsideScope",
                          "treatmentResolver")));

    /** Key prefixes whose suffix is free-form (a plan name, a product name, a jurisdiction). */
    private static final List<String> KNOWN_PREFIXES = Collections.unmodifiableList(
            Arrays.asList("priceMode.plans.", "priceMode.products.", "rates."));

    private List<String> findProblems(final Properties p) {
        final List<String> found = new ArrayList<String>();

        for (final String rawKey : p.stringPropertyNames()) {
            if (!rawKey.startsWith(PROPERTY_PREFIX)) {
                continue;
            }
            final String key = rawKey.substring(PROPERTY_PREFIX.length());
            if (KNOWN_KEYS.contains(key)) {
                continue;
            }
            boolean prefixed = false;
            for (final String prefix : KNOWN_PREFIXES) {
                if (key.startsWith(prefix) && key.length() > prefix.length()) {
                    prefixed = true;
                    break;
                }
            }
            if (!prefixed) {
                found.add("Unrecognised property '" + rawKey + "'. It is being ignored;"
                          + " check the spelling against docs/CONFIGURATION.md.");
            } else if (key.startsWith("rates.")
                       && VatRateTable.parseRate(p.getProperty(rawKey)) == null
                       && !key.endsWith(".from") && !key.endsWith(".to")) {
                found.add("Rate '" + rawKey + "' = '" + p.getProperty(rawKey)
                          + "' could not be read. Write a fraction (0.20) or a percentage (20%);"
                          + " a bare number greater than 1 is rejected as ambiguous.");
            }
        }

        if (rateTable.all().isEmpty()) {
            found.add("No VAT rates are configured, so every supply resolves to OUTSIDE_SCOPE"
                      + " and no VAT will be charged at all.");
        } else if (rateTable.findRate(supplierCountry, "standard", LocalDate.now()) == null) {
            // Deliberately looks for a standard rate in force today, not merely for the
            // jurisdiction. A misspelt rate kind (rates.GB.standrd) registers the jurisdiction
            // while leaving domestic supplies untaxed, which is exactly the silent failure this
            // whole diagnostic exists to catch.
            found.add("No standard rate is in force today for the supplier country "
                      + supplierCountry + ", so domestic supplies will resolve to OUTSIDE_SCOPE"
                      + " and no VAT will be charged on them. Check the rate kind is spelt"
                      + " 'standard' and that its validity dates cover today.");
        }

        if (!ossRegistered && euB2cTreatment == VatTreatmentKind.DESTINATION) {
            for (final String country : euCountries) {
                if (!country.equals(supplierCountry) && rateTable.hasJurisdiction(country)) {
                    found.add("EU rates are configured and euB2cTreatment is DESTINATION, but"
                              + " ossRegistered is false, so EU consumers will get "
                              + euB2cUnregisteredTreatment + " instead. Set ossRegistered=true once"
                              + " the One Stop Shop registration exists.");
                    break;
                }
            }
        }

        if (vatNumberValidation == VatNumberValidationMode.NONE) {
            found.add("vatNumberValidation is NONE, so any VAT number on file is trusted without"
                      + " any check and the reverse charge will be applied on it. Use EXTERNAL in"
                      + " production.");
        } else if (vatNumberValidation == VatNumberValidationMode.CHECKSUM) {
            found.add("vatNumberValidation is CHECKSUM, which proves the number is well formed but"
                      + " not that the registration exists. Use EXTERNAL in production.");
        }

        if (roundingScale < 0 || roundingScale > 6) {
            found.add("rounding.scale is " + roundingScale + ", which is almost certainly wrong."
                      + " Currency amounts normally use 2.");
        }

        return found;
    }

    // --- parsing ----------------------------------------------------------

    private static String get(final Properties p, final String key, final String fallback) {
        final String value = p.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static Map<String, PriceMode> readPriceModeOverrides(final Properties p, final String prefix) {
        final Map<String, PriceMode> overrides = new LinkedHashMap<String, PriceMode>();
        for (final String key : p.stringPropertyNames()) {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                final PriceMode mode = PriceMode.parse(p.getProperty(key), null);
                if (mode != null) {
                    overrides.put(key.substring(prefix.length()), mode);
                }
            }
        }
        return Collections.unmodifiableMap(overrides);
    }

    private static Set<String> readCountrySet(final String csv) {
        final Set<String> countries = new LinkedHashSet<String>();
        if (csv != null) {
            for (final String part : csv.split(",")) {
                final String trimmed = part.trim().toUpperCase();
                if (!trimmed.isEmpty()) {
                    countries.add(trimmed);
                }
            }
        }
        return Collections.unmodifiableSet(countries);
    }

    private static int readInt(final String value, final int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (final RuntimeException e) {
            return fallback;
        }
    }

    private static RoundingMode readRoundingMode(final String value) {
        try {
            return RoundingMode.valueOf(value.trim().toUpperCase());
        } catch (final RuntimeException e) {
            return RoundingMode.HALF_UP;
        }
    }

    private static VatTreatmentKind readTreatment(final String value, final VatTreatmentKind fallback) {
        try {
            return VatTreatmentKind.valueOf(value.trim().toUpperCase());
        } catch (final RuntimeException e) {
            return fallback;
        }
    }

    /** How much proof a VAT number needs before the reverse charge is applied. */
    public enum VatNumberValidationMode {

        /** Format and checksum only. Fast, offline, and not proof of registration. */
        CHECKSUM,

        /**
         * Requires a recent successful external check, recorded on the account as
         * {@code customerVatNumberValidatedAt}. Without one, the reverse charge is refused and
         * VAT is charged, because under-collecting is the expensive direction of this error.
         */
        EXTERNAL,

        /** Trust whatever is on file. Only sensible in development. */
        NONE;

        static VatNumberValidationMode parse(final String value) {
            if (value == null) {
                return CHECKSUM;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (final IllegalArgumentException e) {
                return CHECKSUM;
            }
        }
    }
}
