/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.core;

/**
 * How to read a catalogue price.
 *
 * Kill Bill itself has no notion of tax-inclusive pricing: core, the plugin API and
 * {@code PluginTaxCalculator} all treat catalogue amounts as net and simply append tax. The
 * AvaTax plugin's DTO even carries a {@code taxIncluded} flag that is never assigned. So
 * inclusive pricing is implemented here, by rewriting the charge line down to its net amount in
 * the same pass that adds the TAX item.
 */
public enum PriceMode {

    /**
     * Catalogue prices exclude VAT. VAT is added on top and the invoice total increases.
     * This is Kill Bill's native assumption and the right default for B2B.
     */
    EXCLUSIVE,

    /**
     * Catalogue prices include VAT. The VAT is extracted, the charge line is reduced to net, and
     * the invoice total is unchanged. Normal for consumer pricing, where the advertised price is
     * what gets charged.
     */
    INCLUSIVE;

    public static PriceMode parse(final String value, final PriceMode fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        final String normalised = value.trim().toUpperCase();
        if ("INCLUSIVE".equals(normalised) || "INCLUDING".equals(normalised) || "GROSS".equals(normalised)) {
            return INCLUSIVE;
        }
        if ("EXCLUSIVE".equals(normalised) || "EXCLUDING".equals(normalised) || "NET".equals(normalised)) {
            return EXCLUSIVE;
        }
        return fallback;
    }
}
