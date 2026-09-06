/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.resolve;

import java.time.LocalDate;

/**
 * Everything a resolver is allowed to see about one supply.
 *
 * Deliberately free of Kill Bill types. The calculator does the extraction, which keeps resolvers
 * testable and means a custom resolver does not need to understand Kill Bill's object model.
 *
 * Note there is no network access here and no API handle. Resolution runs inside invoice
 * generation, so it must be fast and side-effect free. VAT number validation happens out of band
 * and arrives here as {@link #isCustomerVatNumberValidated()}.
 */
public final class VatTreatmentRequest {

    private final String customerCountry;
    private final String customerVatNumber;
    private final boolean customerVatNumberValidated;
    private final String planName;
    private final String productName;
    private final LocalDate taxPoint;

    private VatTreatmentRequest(final Builder builder) {
        this.customerCountry = normalise(builder.customerCountry);
        this.customerVatNumber = builder.customerVatNumber == null
                                 ? null : builder.customerVatNumber.replaceAll("\\s", "").toUpperCase();
        this.customerVatNumberValidated = builder.customerVatNumberValidated;
        this.planName = builder.planName;
        this.productName = builder.productName;
        this.taxPoint = builder.taxPoint;
    }

    /** ISO 3166-1 alpha-2, upper case, or null when the account has no country set. */
    public String getCustomerCountry() {
        return customerCountry;
    }

    /** Normalised: no spaces, upper case. Null when none is on file. */
    public String getCustomerVatNumber() {
        return customerVatNumber;
    }

    /** Whether that number has passed whatever validation the configuration demands. */
    public boolean isCustomerVatNumberValidated() {
        return customerVatNumberValidated;
    }

    public String getPlanName() {
        return planName;
    }

    public String getProductName() {
        return productName;
    }

    /** Time of supply. Rates are looked up as at this date, not as at today. */
    public LocalDate getTaxPoint() {
        return taxPoint;
    }

    private static String normalise(final String country) {
        return country == null || country.trim().isEmpty() ? null : country.trim().toUpperCase();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String customerCountry;
        private String customerVatNumber;
        private boolean customerVatNumberValidated;
        private String planName;
        private String productName;
        private LocalDate taxPoint;

        public Builder customerCountry(final String value) {
            this.customerCountry = value;
            return this;
        }

        public Builder customerVatNumber(final String value) {
            this.customerVatNumber = value;
            return this;
        }

        public Builder customerVatNumberValidated(final boolean value) {
            this.customerVatNumberValidated = value;
            return this;
        }

        public Builder planName(final String value) {
            this.planName = value;
            return this;
        }

        public Builder productName(final String value) {
            this.productName = value;
            return this;
        }

        public Builder taxPoint(final LocalDate value) {
            this.taxPoint = value;
            return this;
        }

        public VatTreatmentRequest build() {
            return new VatTreatmentRequest(this);
        }
    }
}
