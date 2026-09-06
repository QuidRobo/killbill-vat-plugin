/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * The per-country structural patterns and the check-digit algorithms follow the published
 * national specifications. Some were cross-checked against the VATIN validator in
 * bgandon/killbill-simple-tax-plugin, also Apache 2.0, Copyright 2015-2017 Benjamin Gandon.
 */
package com.quidrobo.killbill.vat.vies;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Offline VAT number validation: structure for every supported country, plus check digits where
 * the algorithm is published and unambiguous.
 *
 * This is a cheap first gate, not proof of registration. A number can be perfectly well formed
 * and belong to nobody, or belong to a business whose registration was cancelled last week. Only
 * VIES (or HMRC's own service for GB) tells you whether a registration is live, and only its
 * consultation number is worth anything in an audit. Configure
 * {@code vatNumberValidation = EXTERNAL} in production so that the reverse charge requires a
 * real check rather than arithmetic.
 */
public final class VatNumberFormat {

    private static final Map<String, Pattern> STRUCTURE = new HashMap<String, Pattern>();

    static {
        STRUCTURE.put("AT", Pattern.compile("U[0-9]{8}"));
        STRUCTURE.put("BE", Pattern.compile("[01][0-9]{9}"));
        STRUCTURE.put("BG", Pattern.compile("[0-9]{9,10}"));
        STRUCTURE.put("CY", Pattern.compile("[0-9]{8}[A-Z]"));
        STRUCTURE.put("CZ", Pattern.compile("[0-9]{8,10}"));
        STRUCTURE.put("DE", Pattern.compile("[0-9]{9}"));
        STRUCTURE.put("DK", Pattern.compile("[0-9]{8}"));
        STRUCTURE.put("EE", Pattern.compile("[0-9]{9}"));
        STRUCTURE.put("EL", Pattern.compile("[0-9]{9}"));
        STRUCTURE.put("GR", Pattern.compile("[0-9]{9}"));
        STRUCTURE.put("ES", Pattern.compile("[A-Z0-9][0-9]{7}[A-Z0-9]"));
        STRUCTURE.put("FI", Pattern.compile("[0-9]{8}"));
        STRUCTURE.put("FR", Pattern.compile("[A-Z0-9]{2}[0-9]{9}"));
        STRUCTURE.put("GB", Pattern.compile("([0-9]{9}([0-9]{3})?|(GD|HA)[0-9]{3})"));
        STRUCTURE.put("HR", Pattern.compile("[0-9]{11}"));
        STRUCTURE.put("HU", Pattern.compile("[0-9]{8}"));
        STRUCTURE.put("IE", Pattern.compile("([0-9]{7}[A-W][A-I]?|[0-9][A-Z+*][0-9]{5}[A-W])"));
        STRUCTURE.put("IT", Pattern.compile("[0-9]{11}"));
        STRUCTURE.put("LT", Pattern.compile("([0-9]{9}|[0-9]{12})"));
        STRUCTURE.put("LU", Pattern.compile("[0-9]{8}"));
        STRUCTURE.put("LV", Pattern.compile("[0-9]{11}"));
        STRUCTURE.put("MT", Pattern.compile("[0-9]{8}"));
        STRUCTURE.put("NL", Pattern.compile("[0-9]{9}B[0-9]{2}"));
        STRUCTURE.put("PL", Pattern.compile("[0-9]{10}"));
        STRUCTURE.put("PT", Pattern.compile("[0-9]{9}"));
        STRUCTURE.put("RO", Pattern.compile("[0-9]{2,10}"));
        STRUCTURE.put("SE", Pattern.compile("[0-9]{12}"));
        STRUCTURE.put("SI", Pattern.compile("[0-9]{8}"));
        STRUCTURE.put("SK", Pattern.compile("[0-9]{10}"));
        STRUCTURE.put("XI", Pattern.compile("([0-9]{9}([0-9]{3})?|(GD|HA)[0-9]{3})"));
    }

    private VatNumberFormat() {
    }

    /** Strips spaces, dots and dashes and upper-cases. Null-safe. */
    public static String normalise(final String vatNumber) {
        if (vatNumber == null) {
            return null;
        }
        final String cleaned = vatNumber.replaceAll("[\\s.\\-]", "").toUpperCase();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** The two-letter country prefix of a normalised number, or null when it has none. */
    public static String countryOf(final String normalisedVatNumber) {
        if (normalisedVatNumber == null || normalisedVatNumber.length() < 3) {
            return null;
        }
        final String prefix = normalisedVatNumber.substring(0, 2);
        return STRUCTURE.containsKey(prefix) ? prefix : null;
    }

    /**
     * Structure plus check digits where implemented.
     *
     * Returns false for a number whose country prefix is unknown, since guessing is worse than
     * refusing: an unrecognised prefix falls through to the B2C branch and VAT is charged.
     */
    public static boolean isPlausible(final String vatNumber) {
        final String normalised = normalise(vatNumber);
        final String country = countryOf(normalised);
        if (country == null) {
            return false;
        }
        final String body = normalised.substring(2);
        if (!STRUCTURE.get(country).matcher(body).matches()) {
            return false;
        }
        if (isAllZeroDigits(body)) {
            // GB000000000, IT00000000000 and NL000000000B01 all satisfy their national check
            // digits. Arithmetic cannot distinguish a valid number from a placeholder, so an
            // all-zero body is rejected explicitly.
            return false;
        }
        return checkDigits(country, body);
    }

    /**
     * Whether every digit in the body is zero, ignoring the fixed letters some formats carry.
     *
     * Testing the raw body would let NL000000000B01 through, because the B is not a zero. That is
     * exactly the number somebody types to get past a required field, and it passes the Dutch
     * check digit.
     */
    private static boolean isAllZeroDigits(final String body) {
        boolean sawDigit = false;
        for (int i = 0; i < body.length(); i++) {
            final char c = body.charAt(i);
            if (c >= '1' && c <= '9') {
                return false;
            }
            if (c == '0') {
                sawDigit = true;
            }
        }
        return sawDigit;
    }

    /**
     * True when the check digits pass, or when no algorithm is implemented for that country.
     *
     * Returning true for unimplemented countries is intentional: a structurally valid number
     * should not be rejected merely because this library does not know that country's checksum.
     * VIES is the authority either way.
     */
    static boolean checkDigits(final String country, final String body) {
        try {
            if ("GB".equals(country) || "XI".equals(country)) {
                return checkGb(body);
            }
            if ("DE".equals(country)) {
                return checkMod1110(body);
            }
            if ("IT".equals(country)) {
                return checkIt(body);
            }
            if ("NL".equals(country)) {
                return checkNl(body);
            }
            if ("FR".equals(country)) {
                return checkFr(body);
            }
        } catch (final RuntimeException e) {
            return false;
        }
        return true;
    }

    /** UK: weighted sum of the first seven digits plus the check pair, modulo 97, old or new series. */
    private static boolean checkGb(final String body) {
        if (body.startsWith("GD") || body.startsWith("HA")) {
            // Government departments and health authorities carry no check digits.
            return true;
        }
        final String digits = body.length() == 12 ? body.substring(0, 9) : body;
        if (digits.length() != 9) {
            return false;
        }
        final int[] weights = {8, 7, 6, 5, 4, 3, 2};
        int total = 0;
        for (int i = 0; i < 7; i++) {
            total += digit(digits, i) * weights[i];
        }
        total += Integer.parseInt(digits.substring(7, 9));
        return total % 97 == 0 || (total + 55) % 97 == 0;
    }

    /** Germany: ISO 7064 MOD 11,10. */
    private static boolean checkMod1110(final String body) {
        int product = 10;
        for (int i = 0; i < body.length() - 1; i++) {
            int sum = (digit(body, i) + product) % 10;
            if (sum == 0) {
                sum = 10;
            }
            product = (2 * sum) % 11;
        }
        final int check = (11 - product) % 10;
        return check == digit(body, body.length() - 1);
    }

    /** Italy: Luhn over the first ten digits. */
    private static boolean checkIt(final String body) {
        int total = 0;
        for (int i = 0; i < 10; i++) {
            int value = digit(body, i);
            if (i % 2 == 1) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            total += value;
        }
        final int check = (10 - (total % 10)) % 10;
        return check == digit(body, 10);
    }

    /** Netherlands: weights nine down to two over the first eight digits, modulo 11. */
    private static boolean checkNl(final String body) {
        int total = 0;
        for (int i = 0; i < 8; i++) {
            total += digit(body, i) * (9 - i);
        }
        final int check = total % 11;
        return check < 10 && check == digit(body, 8);
    }

    /** France: numeric key validated against the SIREN, modulo 97. Alphabetic keys are not checkable here. */
    private static boolean checkFr(final String body) {
        final String key = body.substring(0, 2);
        if (!key.matches("[0-9]{2}")) {
            return true;
        }
        final long siren = Long.parseLong(body.substring(2));
        return Integer.parseInt(key) == (int) ((12 + 3 * (siren % 97)) % 97);
    }

    private static int digit(final String value, final int index) {
        final char c = value.charAt(index);
        if (c < '0' || c > '9') {
            throw new NumberFormatException("not a digit: " + c);
        }
        return c - '0';
    }
}
