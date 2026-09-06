/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.quidrobo.killbill.vat.core.PriceMode;

/**
 * The VAT arithmetic, with no Kill Bill types anywhere in it so it can be tested on its own.
 *
 * Two ways a catalogue price can be entered:
 *
 * <ul>
 *   <li>{@link PriceMode#EXCLUSIVE}: the catalogue price is the net amount. VAT is added on top
 *       and the invoice total goes up.</li>
 *   <li>{@link PriceMode#INCLUSIVE}: the catalogue price already contains VAT. The VAT is
 *       extracted from it, the charge line is reduced to its net amount, and the invoice total
 *       is unchanged. What the customer was quoted is what the customer pays.</li>
 * </ul>
 *
 * In inclusive mode the VAT is deliberately computed as {@code gross - net} rather than
 * {@code gross * rate / (1 + rate)}. Rounding each half independently lets them disagree by a
 * penny, and a VAT invoice whose net and VAT do not add up to the total is a finance ticket.
 */
public final class VatComputation {

    private VatComputation() {
    }

    /**
     * The price mode actually used for an item, which is not always the configured one.
     *
     * Rewriting an item that already carries adjustments would silently change what those
     * adjustments were computed against, so the rewrite is off for an adjusted item. The split
     * has to switch with it: extracting VAT out of the gross while leaving the charge line gross
     * produced a tax amount matching no rate and a total matching nothing at all.
     *
     * This lives here, beside the arithmetic, rather than on the calculator. It is a decision
     * about how to read a price, it needs no Kill Bill types, and putting it on a subclass of
     * {@code PluginTaxCalculator} meant a test could not call it without loading that whole
     * hierarchy and everything it drags in.
     */
    public static PriceMode effectivePriceMode(final PriceMode configured, final boolean itemIsAdjusted) {
        return configured == PriceMode.INCLUSIVE && itemIsAdjusted ? PriceMode.EXCLUSIVE : configured;
    }

    /**
     * Splits a catalogue amount into its net and VAT parts.
     *
     * @param itemAmount   the amount as the catalogue produced it: net when EXCLUSIVE, gross when INCLUSIVE
     * @param rate         the VAT rate as a fraction, so 0.20 for 20%. Never null; use ZERO for no VAT
     * @param priceMode    how to read {@code itemAmount}
     * @param scale        currency scale, normally 2
     * @param roundingMode rounding to apply
     * @return the split, where {@code net + vat} always equals the gross exactly
     */
    public static VatSplit split(final BigDecimal itemAmount,
                                 final BigDecimal rate,
                                 final PriceMode priceMode,
                                 final int scale,
                                 final RoundingMode roundingMode) {
        if (itemAmount == null) {
            return new VatSplit(BigDecimal.ZERO.setScale(scale), BigDecimal.ZERO.setScale(scale));
        }
        final BigDecimal effectiveRate = rate == null ? BigDecimal.ZERO : rate;

        if (effectiveRate.compareTo(BigDecimal.ZERO) == 0) {
            // Zero rated, reverse charge and outside scope all land here. The charge is untouched
            // in both price modes: there is no VAT inside an inclusive price of zero percent.
            return new VatSplit(itemAmount.setScale(scale, roundingMode), BigDecimal.ZERO.setScale(scale));
        }

        if (priceMode == PriceMode.INCLUSIVE) {
            final BigDecimal gross = itemAmount.setScale(scale, roundingMode);
            final BigDecimal net = gross.divide(BigDecimal.ONE.add(effectiveRate), scale, roundingMode);
            return new VatSplit(net, gross.subtract(net));
        }

        final BigDecimal net = itemAmount.setScale(scale, roundingMode);
        return new VatSplit(net, net.multiply(effectiveRate).setScale(scale, roundingMode));
    }

    /** The net and VAT halves of a charge. */
    public static final class VatSplit {

        private final BigDecimal net;
        private final BigDecimal vat;

        VatSplit(final BigDecimal net, final BigDecimal vat) {
            this.net = net;
            this.vat = vat;
        }

        /** The amount the charge line should show, excluding VAT. */
        public BigDecimal getNet() {
            return net;
        }

        /** The amount of the TAX item. */
        public BigDecimal getVat() {
            return vat;
        }

        /** Always exactly {@code net + vat}. */
        public BigDecimal getGross() {
            return net.add(vat);
        }

        /**
         * True when the charge line has to be rewritten, which is only ever the inclusive case.
         * In exclusive mode the original amount is already the net amount.
         */
        public boolean requiresRewrite(final BigDecimal originalItemAmount) {
            return originalItemAmount != null && net.compareTo(originalItemAmount) != 0;
        }

        @Override
        public String toString() {
            return "net=" + net + " vat=" + vat;
        }
    }
}
