/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.util;

/**
 * Account custom fields the plugin reads.
 *
 * Kill Bill's {@code Account} has no VAT number field, so this is where the B2B facts live.
 * Set them with {@code POST /1.0/kb/accounts/{accountId}/customFields}.
 */
public final class VatCustomFields {

    /** The customer's VAT registration number, in any format. Normalised before use. */
    public static final String CUSTOMER_VAT_NUMBER = "customerVatNumber";

    /**
     * ISO-8601 date of the last successful external validation of that number, for example the
     * day VIES returned a consultation number. Required when
     * {@code vatNumberValidation = EXTERNAL}; ignored otherwise.
     */
    public static final String CUSTOMER_VAT_NUMBER_VALIDATED_AT = "customerVatNumberValidatedAt";

    /**
     * Optional override of the country used for the place of supply, when the billing address
     * country is not the right answer.
     */
    public static final String CUSTOMER_TAX_COUNTRY = "customerTaxCountry";

    private VatCustomFields() {
    }
}
