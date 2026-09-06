/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.resolve;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Properties;

import org.testng.annotations.Test;

import com.quidrobo.killbill.vat.core.VatConfig;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * The decision tree, which is where the plugin either gets the tax right or costs somebody money.
 *
 * Every case here has a real-world counterpart, and the comments say which, because in twelve
 * months the failing assertion is the only thing that will explain why the rule exists.
 */
public class TestDigitalServicesVatResolver {

    private static final String PREFIX = "org.killbill.billing.plugin.vat.";
    private static final String VALID_DE = "DE136695976";
    private static final String VALID_GB = "GB484549056";

    // ------------------------------------------------------------------ domestic

    @Test(groups = "fast")
    public void testDomesticSupplyIsChargedAtTheSupplierRate() {
        final DigitalServicesVatResolver resolver = ukResolver();

        final VatTreatment consumer = resolver.resolve(request("GB", null, false));
        assertEquals(consumer.getKind(), VatTreatmentKind.DOMESTIC);
        assertRate(consumer, "0.20");
        assertEquals(consumer.getDescription(), "VAT 20%");

        // The reverse charge does not apply domestically: a UK business selling to a UK business
        // charges UK VAT like anyone else.
        final VatTreatment business = resolver.resolve(request("GB", VALID_GB, true));
        assertEquals(business.getKind(), VatTreatmentKind.DOMESTIC,
                     "a domestic B2B supply is not a reverse charge");
    }

    // ------------------------------------------------------------------ reverse charge

    @Test(groups = "fast")
    public void testValidatedEuBusinessIsAReverseCharge() {
        final VatTreatment treatment = ukResolver().resolve(request("DE", VALID_DE, true));

        assertEquals(treatment.getKind(), VatTreatmentKind.REVERSE_CHARGE);
        assertRate(treatment, "0");
        assertNotNull(treatment.getLegend(), "a reverse charge invoice must carry the legend");
        assertTrue(treatment.getLegend().contains("Reverse charge"));
        assertEquals(treatment.getCustomerVatNumber(), VALID_DE,
                     "the customer's VAT number has to appear on a reverse charge invoice");
    }

    /**
     * The asymmetry that protects the supplier: an unvalidated number is not evidence of business
     * status. Treating it as one is how a supplier ends up owing VAT it never collected.
     */
    @Test(groups = "fast")
    public void testUnvalidatedVatNumberIsNotAReverseCharge() {
        final VatTreatment treatment = ukResolver().resolve(request("DE", VALID_DE, false));

        assertEquals(treatment.getKind(), VatTreatmentKind.DESTINATION);
        assertRate(treatment, "0.19");
    }

    /**
     * A GB number on a German account proves nothing about a German business. Before this check,
     * any well-formed number from anywhere zero-rated a supply to any reverse charge country.
     */
    @Test(groups = "fast")
    public void testVatNumberFromAnotherCountryIsIgnored() {
        final VatTreatment treatment = ukResolver().resolve(request("DE", VALID_GB, true));

        assertEquals(treatment.getKind(), VatTreatmentKind.DESTINATION,
                     "a mismatched country prefix must not buy a zero rating");
    }

    /** Greece is EL on a VAT number and GR as a country code. Both spellings mean Greece. */
    @Test(groups = "fast")
    public void testGreekAndNorthernIrishPrefixesAreReconciled() {
        assertTrue(DigitalServicesVatResolver.countryAgrees("EL123456789", "GR"));
        assertTrue(DigitalServicesVatResolver.countryAgrees("GR123456789", "EL"));
        assertTrue(DigitalServicesVatResolver.countryAgrees("XI484549056", "GB"),
                   "a Northern Ireland number sits on an account whose country is GB");
        assertFalse(DigitalServicesVatResolver.countryAgrees("DE136695976", "FR"));
        assertTrue(DigitalServicesVatResolver.countryAgrees("ZZ123", "DE"),
                   "an unrecognised prefix yields no evidence either way, so it does not veto");
    }

    // ------------------------------------------------------------------ EU B2C and OSS

    /**
     * Holding a rate for Germany is not authority to charge German VAT. Without an OSS
     * registration there is no mechanism to remit it, so collecting it would be collecting money
     * that cannot be handed over.
     */
    @Test(groups = "fast")
    public void testEuB2cWithoutAnOssRegistrationFallsBack() {
        final Properties p = base("GB");
        p.setProperty(PREFIX + "rates.DE.standard", "0.19");
        final VatConfig config = new VatConfig(p);
        assertFalse(config.isOssRegistered(), "ossRegistered has to default to false");

        final VatTreatment treatment = resolverFor(config).resolve(request("DE", null, false));

        assertEquals(treatment.getKind(), VatTreatmentKind.DOMESTIC);
        assertRate(treatment, "0.20");
        assertTrue(treatment.getReason().contains("OSS"), "the reason has to name the missing piece");
    }

    @Test(groups = "fast")
    public void testEuB2cWithAnOssRegistrationUsesTheDestinationRate() {
        final Properties p = base("GB");
        p.setProperty(PREFIX + "rates.DE.standard", "0.19");
        p.setProperty(PREFIX + "ossRegistered", "true");
        final DigitalServicesVatResolver resolver = resolverFor(new VatConfig(p));

        final VatTreatment consumer = resolver.resolve(request("DE", null, false));
        assertEquals(consumer.getKind(), VatTreatmentKind.DESTINATION);
        assertRate(consumer, "0.19");

        assertEquals(resolver.resolve(request("DE", VALID_DE, true)).getKind(),
                     VatTreatmentKind.REVERSE_CHARGE,
                     "the OSS flag governs B2C only; it must not disturb the reverse charge");
    }

    // ------------------------------------------------------------------ rest of world

    @Test(groups = "fast")
    public void testCustomerOutsideTheReverseChargeAreaIsOutsideScope() {
        final DigitalServicesVatResolver resolver = ukResolver();

        final VatTreatment consumer = resolver.resolve(request("US", null, false));
        assertEquals(consumer.getKind(), VatTreatmentKind.OUTSIDE_SCOPE);
        assertTrue(consumer.getReason().contains("US"));

        // A US business is outside scope, not a reverse charge. Both are 0%, but a reverse charge
        // legend on a US invoice is simply a false statement about US law.
        final VatTreatment business = resolver.resolve(request("US", "US123456", true));
        assertEquals(business.getKind(), VatTreatmentKind.OUTSIDE_SCOPE);
    }

    /**
     * Where the supplier holds a local registration, local VAT is charged and the recorded reason
     * has to say so. It used to fall through to the B2C branch and record "VAT number is not
     * validated" about a customer whose number was, which is the one thing an audit trail must not
     * do.
     */
    @Test(groups = "fast")
    public void testBusinessInACountryWhereTheSupplierIsRegisteredIsChargedLocally() {
        final Properties p = base("GB");
        p.setProperty(PREFIX + "rates.NO.standard", "0.25");
        p.setProperty(PREFIX + "registeredCountries", "NO");
        final DigitalServicesVatResolver resolver = resolverFor(new VatConfig(p));

        final VatTreatment business = resolver.resolve(request("NO", "NO999999999MVA", true));
        assertEquals(business.getKind(), VatTreatmentKind.DESTINATION);
        assertRate(business, "0.25");
        assertTrue(business.getReason().contains("registration"),
                   "the reason must reflect the registration, not a validation failure");
        assertFalse(business.getReason().contains("not validated"));

        final VatTreatment consumer = resolver.resolve(request("NO", null, false));
        assertEquals(consumer.getKind(), VatTreatmentKind.DESTINATION);
        assertRate(consumer, "0.25");
    }

    /** The reverse charge area is configuration, so an EU supplier can add GB to it after Brexit. */
    @Test(groups = "fast")
    public void testTheReverseChargeAreaIsConfigurable() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "supplierCountry", "IE");
        p.setProperty(PREFIX + "rates.IE.standard", "0.23");
        p.setProperty(PREFIX + "reverseChargeCountries", "GB,DE,FR");

        assertEquals(resolverFor(new VatConfig(p)).resolve(request("GB", VALID_GB, true)).getKind(),
                     VatTreatmentKind.REVERSE_CHARGE);
    }

    // ------------------------------------------------------------------ failure modes

    @Test(groups = "fast")
    public void testAnAccountWithNoCountryDefaultsToDomestic() {
        // Under-collecting is the expensive direction: the supplier carries the liability, whereas
        // over-collecting is a refund.
        final VatTreatment treatment = ukResolver().resolve(request(null, null, false));

        assertEquals(treatment.getKind(), VatTreatmentKind.DOMESTIC);
        assertTrue(treatment.getReason().contains("no country"));
    }

    @Test(groups = "fast")
    public void testUnknownCountryTreatmentIsHonoured() {
        final Properties p = base("GB");
        p.setProperty(PREFIX + "unknownCountryTreatment", "OUTSIDE_SCOPE");

        assertEquals(resolverFor(new VatConfig(p)).resolve(request(null, null, false)).getKind(),
                     VatTreatmentKind.OUTSIDE_SCOPE);
    }

    /**
     * A missing rate must never become a silent 0%. That failure mode under-collects for as long
     * as nobody notices, which is normally until a VAT return is late.
     */
    @Test(groups = "fast")
    public void testAMissingRateIsNotSilentlyZeroRated() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "supplierCountry", "GB");

        final VatTreatment treatment = resolverFor(new VatConfig(p)).resolve(request("GB", null, false));

        assertEquals(treatment.getKind(), VatTreatmentKind.OUTSIDE_SCOPE);
        assertTrue(treatment.getReason().contains("no standard rate configured"),
                   "the reason has to name the gap so it shows up in the log");
    }

    @Test(groups = "fast")
    public void testRatesAreLookedUpAtTheTaxPointNotToday() {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "supplierCountry", "GB");
        p.setProperty(PREFIX + "rates.GB.standard.0.rate", "0.175");
        p.setProperty(PREFIX + "rates.GB.standard.0.from", "2008-12-01");
        p.setProperty(PREFIX + "rates.GB.standard.0.to", "2011-01-04");
        p.setProperty(PREFIX + "rates.GB.standard.1.rate", "0.20");
        p.setProperty(PREFIX + "rates.GB.standard.1.from", "2011-01-04");
        final DigitalServicesVatResolver resolver = resolverFor(new VatConfig(p));

        assertRate(resolver.resolve(requestOn("GB", LocalDate.of(2009, 6, 1))), "0.175");
        assertRate(resolver.resolve(requestOn("GB", LocalDate.of(2026, 9, 1))), "0.20");
    }

    // ------------------------------------------------------------------ helpers

    private static Properties base(final String supplierCountry) {
        final Properties p = new Properties();
        p.setProperty(PREFIX + "supplierCountry", supplierCountry);
        p.setProperty(PREFIX + "rates." + supplierCountry + ".standard", "0.20");
        p.setProperty(PREFIX + "vatNumberValidation", "CHECKSUM");
        return p;
    }

    private static DigitalServicesVatResolver ukResolver() {
        final Properties p = base("GB");
        p.setProperty(PREFIX + "rates.DE.standard", "0.19");
        p.setProperty(PREFIX + "ossRegistered", "true");
        return resolverFor(new VatConfig(p));
    }

    private static DigitalServicesVatResolver resolverFor(final VatConfig config) {
        final DigitalServicesVatResolver resolver = new DigitalServicesVatResolver();
        resolver.init(config, config.getRateTable());
        return resolver;
    }

    private static VatTreatmentRequest request(final String country,
                                               final String vatNumber,
                                               final boolean validated) {
        return VatTreatmentRequest.builder()
                                  .customerCountry(country)
                                  .customerVatNumber(vatNumber)
                                  .customerVatNumberValidated(validated)
                                  .taxPoint(LocalDate.of(2026, 9, 1))
                                  .planName("growth-monthly")
                                  .productName("Growth")
                                  .build();
    }

    private static VatTreatmentRequest requestOn(final String country, final LocalDate taxPoint) {
        return VatTreatmentRequest.builder()
                                  .customerCountry(country)
                                  .taxPoint(taxPoint)
                                  .build();
    }

    private static void assertRate(final VatTreatment treatment, final String expected) {
        assertEquals(treatment.getRate().compareTo(new BigDecimal(expected)), 0,
                     "expected " + expected + " but was " + treatment.getRate().toPlainString());
    }
}
