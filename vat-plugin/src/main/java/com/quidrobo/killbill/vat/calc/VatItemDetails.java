/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.calc;

import java.math.BigDecimal;

import com.quidrobo.killbill.vat.resolve.VatTreatment;

/**
 * Writes the VAT decision onto the tax item itself, so that whoever renders the invoice later does
 * not have to work it out again.
 *
 * <h2>Why the invoice carries this rather than the renderer looking it up</h2>
 *
 * Kill Bill 0.24.x hands an invoice formatter seven arguments and none of them is a
 * {@code TenantContext}, so a formatter cannot make a tenant-scoped API call: it cannot read the
 * account, so it cannot read the customer's country or VAT number. The workaround was to configure
 * one tenant id as a JVM property, which is wrong on a server hosting more than one tenant, and
 * Kill Bill is multi tenanted by design.
 *
 * <p>The plugin has no such problem. It runs with a real {@code InvoiceContext} carrying the tenant
 * id, and at the moment it taxes an item it already knows the treatment, the rate, the customer's
 * country and their VAT number. So it records them on the item, and {@code item_details} is the
 * field Kill Bill provides for exactly this: it is persisted on {@code invoice_items}, copied
 * verbatim by {@code InvoiceItemModelDao} from whatever the plugin returned, and read back with the
 * invoice. The formatter is handed that invoice and needs no tenant, no configuration and no API
 * call at all.
 *
 * <h2>Format</h2>
 *
 * A flat JSON object, written and parsed by hand rather than with Jackson, because this bundle
 * deliberately carries no JSON dependency and the shape is fixed:
 *
 * <pre>
 * {"plugin":"killbill-vat","treatment":"REVERSE_CHARGE","jurisdiction":"DE","rate":"0.0000",
 *  "supplierCountry":"GB","customerCountry":"DE","customerVatNumber":"DE123456789"}
 * </pre>
 *
 * <p>The {@code plugin} discriminator is first and is what the reader checks: {@code item_details}
 * is a shared field that any plugin may write, so anything not marked as ours is left alone rather
 * than guessed at.
 *
 * <p>The reader lives in the formatter bundle, which is independent of this one and must stay that
 * way, so the two ends are separate classes over one agreed format. Both sides pin the literal
 * strings in their tests, so drift on either end fails a build rather than an invoice.
 */
public final class VatItemDetails {

    /** Marks the payload as this plugin's, so other writers of item_details are not misread. */
    public static final String PLUGIN_MARKER = "killbill-vat";

    private VatItemDetails() {
    }

    /**
     * The JSON to store on a tax item, or null when there is nothing worth recording.
     *
     * @param treatment       what was decided, never null in practice
     * @param supplierCountry the country the supplier is established in
     * @param customerCountry the customer's country as the plugin resolved it
     * @param customerVatNumber the customer's VAT number, or null when they have none
     */
    public static String write(final VatTreatment treatment,
                               final String supplierCountry,
                               final String customerCountry,
                               final String customerVatNumber) {
        if (treatment == null) {
            return null;
        }
        final StringBuilder json = new StringBuilder(160);
        json.append("{\"plugin\":\"").append(PLUGIN_MARKER).append('"');
        append(json, "treatment", treatment.getKind() == null ? null : treatment.getKind().name());
        append(json, "jurisdiction", treatment.getJurisdiction());
        append(json, "rate", rate(treatment.getRate()));
        append(json, "supplierCountry", supplierCountry);
        append(json, "customerCountry", customerCountry);
        append(json, "customerVatNumber", customerVatNumber);
        json.append('}');
        return json.toString();
    }

    /** Plain scale-4 decimal. Never scientific notation, which a naive reader would mangle. */
    private static String rate(final BigDecimal value) {
        return value == null ? null : value.setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static void append(final StringBuilder json, final String key, final String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        json.append(",\"").append(key).append("\":\"").append(escape(value.trim())).append('"');
    }

    /**
     * Escapes the two characters that would break the payload, plus the control characters JSON
     * forbids unescaped. Everything here is a country code, a VAT number or an enum name, so this
     * is belt and braces rather than a general purpose encoder, but a legend or jurisdiction that
     * arrives with a quote in it must not produce something the reader cannot parse.
     */
    static String escape(final String value) {
        final StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }
}
