/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.resolve;

import java.math.BigDecimal;

/**
 * The verdict for one supply: the rate to apply, and why.
 *
 * The reason is not decoration. When a tax authority asks in three years why a given invoice
 * carried no VAT, "the resolver said outside scope because the customer country was US and no
 * registration existed for US on that date" is an answer. A bare 0% is not.
 */
public final class VatTreatment {

    private final VatTreatmentKind kind;
    private final String jurisdiction;
    private final BigDecimal rate;
    private final String description;
    private final String legend;
    private final String customerVatNumber;
    private final String reason;

    private VatTreatment(final VatTreatmentKind kind,
                         final String jurisdiction,
                         final BigDecimal rate,
                         final String description,
                         final String legend,
                         final String customerVatNumber,
                         final String reason) {
        this.kind = kind;
        this.jurisdiction = jurisdiction;
        this.rate = rate == null ? BigDecimal.ZERO : rate;
        this.description = description;
        this.legend = legend;
        this.customerVatNumber = customerVatNumber;
        this.reason = reason;
    }

    public static VatTreatment charging(final VatTreatmentKind kind,
                                        final String jurisdiction,
                                        final BigDecimal rate,
                                        final String description,
                                        final String reason) {
        return new VatTreatment(kind, jurisdiction, rate, description, null, null, reason);
    }

    public static VatTreatment reverseCharge(final String jurisdiction,
                                             final String customerVatNumber,
                                             final String legend,
                                             final String reason) {
        return new VatTreatment(VatTreatmentKind.REVERSE_CHARGE, jurisdiction, BigDecimal.ZERO,
                                null, legend, customerVatNumber, reason);
    }

    public static VatTreatment outsideScope(final String jurisdiction, final String legend, final String reason) {
        return new VatTreatment(VatTreatmentKind.OUTSIDE_SCOPE, jurisdiction, BigDecimal.ZERO,
                                null, legend, null, reason);
    }

    public static VatTreatment exempt(final String jurisdiction, final String legend, final String reason) {
        return new VatTreatment(VatTreatmentKind.EXEMPT, jurisdiction, BigDecimal.ZERO,
                                null, legend, null, reason);
    }

    public VatTreatmentKind getKind() {
        return kind;
    }

    /** The taxing jurisdiction, or the jurisdiction the decision was made about. */
    public String getJurisdiction() {
        return jurisdiction;
    }

    /** Fraction, so 0.20 for 20%. Never null, zero for every non-charging treatment. */
    public BigDecimal getRate() {
        return rate;
    }

    /** What the TAX item description says, for example "VAT 20%". Null when nothing is charged. */
    public String getDescription() {
        return description;
    }

    /** Wording the invoice must carry, for example the reverse charge notice. */
    public String getLegend() {
        return legend;
    }

    public String getCustomerVatNumber() {
        return customerVatNumber;
    }

    /** Why this treatment was chosen. Logged, and worth persisting for audit. */
    public String getReason() {
        return reason;
    }

    public boolean chargesVat() {
        return kind.chargesVat() && rate.compareTo(BigDecimal.ZERO) != 0;
    }

    @Override
    public String toString() {
        return kind + " " + jurisdiction + " rate=" + rate + " (" + reason + ")";
    }
}
