/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.formatter;

/**
 * The VAT decision as the plugin recorded it on the tax item, read back at render time.
 *
 * <h2>Why this exists</h2>
 *
 * Kill Bill 0.24.x builds an invoice formatter through a factory method that takes seven arguments,
 * none of them a {@code TenantContext}. A formatter therefore cannot make a tenant-scoped API call:
 * it cannot read the account, so it cannot learn the customer's country or VAT number, and without
 * those it cannot say which VAT treatment an invoice describes.
 *
 * <p>The previous answer was to configure a single tenant id as a JVM property and read the account
 * with it. That works on a server hosting one tenant and is wrong on any other, which is most of
 * them: Kill Bill is multi tenanted, and a formatter that assumes otherwise will read the wrong
 * account or none at all.
 *
 * <p>This is the answer that does not assume anything. The plugin knows the treatment at the moment
 * it taxes the invoice, when it does have a tenant, and writes it onto the tax item's
 * {@code itemDetails}. Kill Bill persists that field on {@code invoice_items} and returns it with
 * the invoice, and the invoice is the one thing this formatter is always given. No tenant, no
 * configuration, no lookup.
 *
 * <h2>Format</h2>
 *
 * A flat JSON object written by the plugin:
 *
 * <pre>
 * {"plugin":"killbill-vat","treatment":"REVERSE_CHARGE","jurisdiction":"DE","rate":"0.0000",
 *  "supplierCountry":"GB","customerCountry":"DE","customerVatNumber":"DE123456789"}
 * </pre>
 *
 * <p>Parsed by hand: this bundle carries no JSON dependency, the shape is fixed and small, and a
 * parser that throws would take an invoice render down over a field that is only ever advisory.
 * Anything unrecognised, malformed, or not carrying this plugin's marker reads as absent, because
 * {@code itemDetails} is a shared field that any plugin may write and a wrong reading of somebody
 * else's payload would print a false legal statement on an invoice.
 */
final class VatItemDetails {

    /** Only payloads carrying this marker are ours. */
    static final String PLUGIN_MARKER = "killbill-vat";

    private final String treatment;
    private final String jurisdiction;
    private final String supplierCountry;
    private final String customerCountry;
    private final String customerVatNumber;

    private VatItemDetails(final String treatment,
                           final String jurisdiction,
                           final String supplierCountry,
                           final String customerCountry,
                           final String customerVatNumber) {
        this.treatment = treatment;
        this.jurisdiction = jurisdiction;
        this.supplierCountry = supplierCountry;
        this.customerCountry = customerCountry;
        this.customerVatNumber = customerVatNumber;
    }

    /**
     * @return the decision recorded on this item, or null when the field is empty, belongs to
     *         another plugin, or cannot be read. Never throws.
     */
    static VatItemDetails parse(final String itemDetails) {
        if (itemDetails == null || itemDetails.isEmpty()) {
            return null;
        }
        try {
            if (!PLUGIN_MARKER.equals(value(itemDetails, "plugin"))) {
                return null;
            }
            return new VatItemDetails(value(itemDetails, "treatment"),
                                      value(itemDetails, "jurisdiction"),
                                      value(itemDetails, "supplierCountry"),
                                      value(itemDetails, "customerCountry"),
                                      value(itemDetails, "customerVatNumber"));
        } catch (final RuntimeException unreadable) {
            // Advisory data on the invoice rendering path. Losing it costs a legend; throwing here
            // would cost the whole invoice.
            return null;
        }
    }

    /**
     * The string value of one key, or null.
     *
     * <p>Matches {@code "key"} followed by a colon and a quoted value, tolerating whitespace on
     * either side of the colon, and unescapes the sequences the writer produces.
     */
    private static String value(final String json, final String key) {
        final String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        at += needle.length();
        while (at < json.length() && Character.isWhitespace(json.charAt(at))) {
            at++;
        }
        if (at >= json.length() || json.charAt(at) != ':') {
            return null;
        }
        at++;
        while (at < json.length() && Character.isWhitespace(json.charAt(at))) {
            at++;
        }
        if (at >= json.length() || json.charAt(at) != '"') {
            return null;
        }
        at++;

        final StringBuilder out = new StringBuilder();
        while (at < json.length()) {
            final char c = json.charAt(at);
            if (c == '"') {
                final String read = out.toString().trim();
                return read.isEmpty() ? null : read;
            }
            if (c == '\\' && at + 1 < json.length()) {
                at++;
                final char escaped = json.charAt(at);
                switch (escaped) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (at + 4 < json.length()) {
                            out.append((char) Integer.parseInt(json.substring(at + 1, at + 5), 16));
                            at += 4;
                        }
                        break;
                    default: out.append(escaped);
                }
            } else {
                out.append(c);
            }
            at++;
        }
        // Unterminated string: the payload is truncated, so report nothing rather than a fragment.
        return null;
    }

    /** One of DOMESTIC, DESTINATION, REVERSE_CHARGE, OUTSIDE_SCOPE, or null. */
    String getTreatment() {
        return treatment;
    }

    String getJurisdiction() {
        return jurisdiction;
    }

    String getSupplierCountry() {
        return supplierCountry;
    }

    String getCustomerCountry() {
        return customerCountry;
    }

    String getCustomerVatNumber() {
        return customerVatNumber;
    }
}
