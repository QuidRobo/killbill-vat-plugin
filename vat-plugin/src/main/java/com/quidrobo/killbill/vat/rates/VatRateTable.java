/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.rates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Effective-dated VAT rates, loaded from plugin configuration.
 *
 * Two accepted property shapes, both under {@code <prefix>rates.}:
 *
 * <pre>
 *   # shorthand: always in force
 *   org.killbill.billing.plugin.vat.rates.GB.standard = 0.20
 *
 *   # dated: an index groups the rate with its validity range
 *   org.killbill.billing.plugin.vat.rates.GB.standard.0.rate = 0.175
 *   org.killbill.billing.plugin.vat.rates.GB.standard.0.from = 2008-12-01
 *   org.killbill.billing.plugin.vat.rates.GB.standard.0.to   = 2010-01-01
 *   org.killbill.billing.plugin.vat.rates.GB.standard.1.rate = 0.20
 *   org.killbill.billing.plugin.vat.rates.GB.standard.1.from = 2011-01-04
 * </pre>
 *
 * Config is the v1 store deliberately. It keeps the plugin dependency-free and installable
 * without a schema, and per-tenant config is already hot-reloadable through Kill Bill's
 * {@code TENANT_CONFIG_CHANGE} event. A database-backed table is the right move once rates are
 * maintained by finance rather than by deploy, and {@link VatRateSource} is the seam for that.
 */
public final class VatRateTable implements VatRateSource {

    private static final Pattern SHORTHAND =
            Pattern.compile("^rates\\.([A-Za-z]{2})\\.([A-Za-z_-]+)$");
    private static final Pattern DATED =
            Pattern.compile("^rates\\.([A-Za-z]{2})\\.([A-Za-z_-]+)\\.(\\d+)\\.(rate|from|to)$");

    private final Map<String, List<VatRate>> byJurisdiction;

    private VatRateTable(final Map<String, List<VatRate>> byJurisdiction) {
        this.byJurisdiction = byJurisdiction;
    }

    /**
     * @param properties the plugin's tenant configuration
     * @param prefix     property prefix, including the trailing dot
     */
    public static VatRateTable fromProperties(final Properties properties, final String prefix) {
        final Map<String, List<VatRate>> parsed = new LinkedHashMap<String, List<VatRate>>();
        // key -> field -> value, for the dated form which spans several properties
        final Map<String, Map<String, String>> dated = new LinkedHashMap<String, Map<String, String>>();

        if (properties != null) {
            for (final String rawKey : properties.stringPropertyNames()) {
                if (!rawKey.startsWith(prefix)) {
                    continue;
                }
                final String key = rawKey.substring(prefix.length());
                final String value = properties.getProperty(rawKey);

                final Matcher datedMatcher = DATED.matcher(key);
                if (datedMatcher.matches()) {
                    final String group = datedMatcher.group(1).toUpperCase() + "|"
                                         + datedMatcher.group(2).toLowerCase() + "|"
                                         + datedMatcher.group(3);
                    Map<String, String> fields = dated.get(group);
                    if (fields == null) {
                        fields = new LinkedHashMap<String, String>();
                        dated.put(group, fields);
                    }
                    fields.put(datedMatcher.group(4), value);
                    continue;
                }

                final Matcher shorthandMatcher = SHORTHAND.matcher(key);
                if (shorthandMatcher.matches()) {
                    final BigDecimal rate = parseRate(value);
                    if (rate != null) {
                        add(parsed, new VatRate(shorthandMatcher.group(1), shorthandMatcher.group(2),
                                                rate, null, null));
                    }
                }
            }
        }

        for (final Map.Entry<String, Map<String, String>> entry : dated.entrySet()) {
            final String[] parts = entry.getKey().split("\\|");
            final BigDecimal rate = parseRate(entry.getValue().get("rate"));
            if (rate == null) {
                continue;
            }
            add(parsed, new VatRate(parts[0], parts[1], rate,
                                    parseDate(entry.getValue().get("from")),
                                    parseDate(entry.getValue().get("to"))));
        }

        return new VatRateTable(parsed);
    }

    @Override
    public VatRate findRate(final String jurisdiction, final String kind, final LocalDate on) {
        if (jurisdiction == null) {
            return null;
        }
        final List<VatRate> candidates = byJurisdiction.get(jurisdiction.trim().toUpperCase());
        if (candidates == null) {
            return null;
        }
        final String wantedKind = (kind == null ? "standard" : kind).trim().toLowerCase();

        VatRate best = null;
        for (final VatRate candidate : candidates) {
            if (!candidate.getKind().equals(wantedKind) || !candidate.appliesOn(on)) {
                continue;
            }
            best = moreSpecific(best, candidate);
        }
        return best;
    }

    /**
     * Picks between two rates that both apply on the same date.
     *
     * Selection must not depend on iteration order. Properties are a Hashtable, so the candidate
     * list arrives in string-hash order, and an earlier version of this method returned whichever
     * overlapping rate happened to come first. Renumbering two identical entries from 0/1 to 2/3
     * changed the tax charged. Now: a dated rate always beats an undated one, and between two
     * dated rates the later start wins, which is what "I added a new rate" means.
     */
    private static VatRate moreSpecific(final VatRate current, final VatRate candidate) {
        if (current == null) {
            return candidate;
        }
        final boolean currentDated = current.getValidFrom() != null || current.getValidTo() != null;
        final boolean candidateDated = candidate.getValidFrom() != null || candidate.getValidTo() != null;
        if (currentDated != candidateDated) {
            return candidateDated ? candidate : current;
        }
        if (current.getValidFrom() == null) {
            return candidate.getValidFrom() == null ? current : candidate;
        }
        if (candidate.getValidFrom() == null) {
            return current;
        }
        return candidate.getValidFrom().isAfter(current.getValidFrom()) ? candidate : current;
    }

    @Override
    public boolean hasJurisdiction(final String jurisdiction) {
        return jurisdiction != null && byJurisdiction.containsKey(jurisdiction.trim().toUpperCase());
    }

    /**
     * Descriptions of rates that collide: two entries for the same jurisdiction and kind whose
     * validity windows overlap, so which one applies depends on configuration the author probably
     * did not intend to be significant.
     */
    public List<String> findOverlaps() {
        final List<String> overlaps = new ArrayList<String>();
        for (final Map.Entry<String, List<VatRate>> entry : byJurisdiction.entrySet()) {
            final List<VatRate> rates = entry.getValue();
            for (int i = 0; i < rates.size(); i++) {
                for (int j = i + 1; j < rates.size(); j++) {
                    final VatRate a = rates.get(i);
                    final VatRate b = rates.get(j);
                    if (a.getKind().equals(b.getKind()) && windowsOverlap(a, b)) {
                        overlaps.add("Rates " + a + " and " + b + " overlap. Close the earlier one"
                                     + " with a 'to' date, or delete one of them.");
                    }
                }
            }
        }
        return overlaps;
    }

    private static boolean windowsOverlap(final VatRate a, final VatRate b) {
        final LocalDate aFrom = a.getValidFrom() == null ? LocalDate.MIN : a.getValidFrom();
        final LocalDate bFrom = b.getValidFrom() == null ? LocalDate.MIN : b.getValidFrom();
        final LocalDate aTo = a.getValidTo() == null ? LocalDate.MAX : a.getValidTo();
        final LocalDate bTo = b.getValidTo() == null ? LocalDate.MAX : b.getValidTo();
        return aFrom.isBefore(bTo) && bFrom.isBefore(aTo);
    }

    /** Every configured rate, for logging at startup so misconfiguration is visible. */
    public List<VatRate> all() {
        final List<VatRate> out = new ArrayList<VatRate>();
        for (final List<VatRate> rates : byJurisdiction.values()) {
            out.addAll(rates);
        }
        return Collections.unmodifiableList(out);
    }

    private static void add(final Map<String, List<VatRate>> target, final VatRate rate) {
        List<VatRate> rates = target.get(rate.getJurisdiction());
        if (rates == null) {
            rates = new ArrayList<VatRate>();
            target.put(rate.getJurisdiction(), rates);
        }
        rates.add(rate);
    }

    /** True when a key under the rates prefix is one the parser actually understands. */
    public static boolean isRecognisedRateKey(final String key) {
        return key != null && (SHORTHAND.matcher(key).matches() || DATED.matcher(key).matches());
    }

    /**
     * Accepts a fraction ({@code 0.20}) or a percentage ({@code 20%}). Anything greater than 1
     * without a percent sign is rejected rather than guessed at: silently reading {@code 20} as
     * 2000% would be catastrophic, and reading it as 20% would reward a typo.
     */
    public static BigDecimal parseRate(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        final String trimmed = value.trim();
        try {
            final BigDecimal rate;
            if (trimmed.endsWith("%")) {
                rate = new BigDecimal(trimmed.substring(0, trimmed.length() - 1).trim())
                        .movePointLeft(2);
            } else {
                rate = new BigDecimal(trimmed);
            }
            // Bounded on both branches. An earlier version guarded only the fraction form, so
            // "2000%" parsed happily as a 2000% rate and "-0.20" as a negative one, which then
            // divides by zero in inclusive mode. A VAT rate is a fraction of one.
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
                return null;
            }
            return rate;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    public static LocalDate parseDate(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (final DateTimeParseException e) {
            return null;
        }
    }
}
