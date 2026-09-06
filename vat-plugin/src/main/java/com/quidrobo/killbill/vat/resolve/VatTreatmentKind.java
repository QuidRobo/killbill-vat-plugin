/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.resolve;

/**
 * How a supply is treated for VAT. This is the answer the whole plugin exists to produce.
 */
public enum VatTreatmentKind {

    /** VAT at the supplier's own rate. UK customer of a UK supplier. */
    DOMESTIC(true),

    /** VAT at the customer's country rate, under OSS or a local registration. */
    DESTINATION(true),

    /** Zero rated, the recipient accounts for the VAT. Requires their VAT number on the invoice. */
    REVERSE_CHARGE(false),

    /** Place of supply is outside the taxing jurisdiction. No VAT, and not a VAT invoice. */
    OUTSIDE_SCOPE(false),

    /** In scope but exempt. */
    EXEMPT(false);

    private final boolean charges;

    VatTreatmentKind(final boolean charges) {
        this.charges = charges;
    }

    /** True when this treatment actually puts VAT on the invoice. */
    public boolean chargesVat() {
        return charges;
    }
}
