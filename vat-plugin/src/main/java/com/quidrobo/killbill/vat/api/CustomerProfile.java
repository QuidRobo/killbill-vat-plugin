/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.customfield.CustomField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.quidrobo.killbill.vat.core.VatConfig;
import com.quidrobo.killbill.vat.util.VatCustomFields;
import com.quidrobo.killbill.vat.vies.VatNumberFormat;

/**
 * The VAT-relevant facts about one customer, read once per invoice run.
 *
 * Everything here comes from the account and its custom fields. Reading it up front keeps the
 * resolver free of Kill Bill types and stops an invoice run making the same lookups per line.
 */
public final class CustomerProfile {

    private static final Logger logger = LoggerFactory.getLogger(CustomerProfile.class);

    private final String taxCountry;
    private final String vatNumber;
    private final boolean vatNumberValidated;

    private CustomerProfile(final String taxCountry,
                            final String vatNumber,
                            final boolean vatNumberValidated) {
        this.taxCountry = taxCountry;
        this.vatNumber = vatNumber;
        this.vatNumberValidated = vatNumberValidated;
    }

    public static CustomerProfile load(final Account account,
                                       final OSGIKillbillAPI killbillAPI,
                                       final VatConfig config,
                                       final TenantContext context) {
        String vatNumber = null;
        String validatedAt = null;
        String taxCountryOverride = null;

        try {
            final List<CustomField> fields = killbillAPI.getCustomFieldUserApi()
                    .getCustomFieldsForObject(account.getId(), ObjectType.ACCOUNT, context);
            if (fields != null) {
                for (final CustomField field : fields) {
                    final String name = field.getFieldName();
                    if (VatCustomFields.CUSTOMER_VAT_NUMBER.equalsIgnoreCase(name)) {
                        vatNumber = field.getFieldValue();
                    } else if (VatCustomFields.CUSTOMER_VAT_NUMBER_VALIDATED_AT.equalsIgnoreCase(name)) {
                        validatedAt = field.getFieldValue();
                    } else if (VatCustomFields.CUSTOMER_TAX_COUNTRY.equalsIgnoreCase(name)) {
                        taxCountryOverride = field.getFieldValue();
                    }
                }
            }
        } catch (final RuntimeException e) {
            // A custom field lookup failure must not abort invoicing. Without a VAT number the
            // customer is treated as a consumer, which charges VAT: the safe direction.
            logger.warn("Unable to read VAT custom fields for account {}; treating as B2C",
                        account.getId(), e);
        }

        final String country = taxCountryOverride != null && !taxCountryOverride.trim().isEmpty()
                               ? taxCountryOverride.trim().toUpperCase()
                               : account.getCountry();

        final String normalised = VatNumberFormat.normalise(vatNumber);
        final boolean validated = isValidated(normalised, validatedAt, config, account.getId());

        return new CustomerProfile(country == null || country.trim().isEmpty()
                                   ? null : country.trim().toUpperCase(),
                                   normalised,
                                   validated);
    }

    /**
     * Whether the VAT number satisfies the configured level of proof.
     *
     * Under {@code EXTERNAL} a recent successful check must be recorded on the account. An
     * expired or missing check is treated as unvalidated, which charges VAT rather than applying
     * the reverse charge, because the supplier carries the liability for getting that wrong.
     */
    private static boolean isValidated(final String normalisedVatNumber,
                                       final String validatedAt,
                                       final VatConfig config,
                                       final UUID accountId) {
        if (normalisedVatNumber == null) {
            return false;
        }
        switch (config.getVatNumberValidation()) {
            case NONE:
                return true;
            case EXTERNAL:
                if (!VatNumberFormat.isPlausible(normalisedVatNumber)) {
                    return false;
                }
                final LocalDate checked = parseDate(validatedAt);
                if (checked == null) {
                    logger.info("Account {} has VAT number {} but no recorded external validation;"
                                + " charging VAT rather than applying the reverse charge",
                                accountId, normalisedVatNumber);
                    return false;
                }
                final boolean fresh = !checked.isBefore(LocalDate.now()
                                                                 .minusDays(config.getValidationMaxAgeDays()));
                if (!fresh) {
                    logger.info("Account {} has a VAT number last validated on {}, older than the"
                                + " configured {} days; charging VAT",
                                accountId, checked, config.getValidationMaxAgeDays());
                }
                return fresh;
            case CHECKSUM:
            default:
                return VatNumberFormat.isPlausible(normalisedVatNumber);
        }
    }

    /** ISO 3166-1 alpha-2, or null when the account has no usable country. */
    public String getTaxCountry() {
        return taxCountry;
    }

    /** Normalised VAT number, or null. */
    public String getVatNumber() {
        return vatNumber;
    }

    public boolean isVatNumberValidated() {
        return vatNumberValidated;
    }

    private static LocalDate parseDate(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            // Tolerate a full timestamp as well as a bare date.
            final String trimmed = value.trim();
            return LocalDate.parse(trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed);
        } catch (final DateTimeParseException e) {
            return null;
        }
    }
}
