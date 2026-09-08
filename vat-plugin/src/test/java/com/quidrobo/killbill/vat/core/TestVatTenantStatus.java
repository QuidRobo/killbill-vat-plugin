/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.core;

import java.util.Properties;
import java.util.UUID;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * VAT is optional, and the plugin has to behave as though it knows that.
 *
 * The plugin is installed once for a whole Kill Bill server, and Kill Bill is multi tenanted, so
 * most tenants on a server may never charge VAT at all. Every diagnostic in {@code VatConfig} was
 * written for a tenant that does charge it, where "no rates are configured" means money is going
 * uncollected. Emitted at a tenant that never asked for VAT, that same sentence is false, it turns
 * the healthcheck red, and it hands the operator instructions to register an invoice plugin they
 * do not want.
 *
 * These tests pin the three states apart: not using VAT, using it but switched off, and using it.
 */
public class TestVatTenantStatus {

    private static final UUID TENANT = UUID.randomUUID();

    /**
     * A configuration handler whose three tenant lookups are dictated by the test.
     *
     * Subclassing rather than mocking keeps this test free of a mocking framework and, more
     * usefully, forces every case to state all three inputs explicitly, which is where the real
     * bug lived: the registration answer was read and then applied without reference to whether
     * the tenant wanted VAT in the first place.
     */
    private static final class FakeHandler extends VatConfigurationHandler {

        private final VatRuntime runtime;
        private final Boolean registered;
        private final String rawTenantConfig;

        FakeHandler(final VatRuntime runtime, final Boolean registered, final String rawTenantConfig) {
            super("killbill-vat", null);
            this.runtime = runtime;
            this.registered = registered;
            this.rawTenantConfig = rawTenantConfig;
        }

        @Override
        public VatRuntime getRuntime(final UUID kbTenantId) {
            return runtime;
        }

        @Override
        public Boolean isRegisteredAsInvoicePlugin(final UUID kbTenantId) {
            return registered;
        }

        @Override
        public String getRawTenantConfiguration(final UUID kbTenantId) {
            return rawTenantConfig;
        }
    }

    private static VatRuntime runtime(final String... keysAndValues) {
        final Properties p = new Properties();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            p.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new VatRuntime(new VatConfig(p));
    }

    /** The default configurable a tenant that has uploaded nothing is served: no rates. */
    private static VatRuntime defaults() {
        return runtime();
    }

    private static VatRuntime workingUkConfig() {
        return runtime(VatConfig.SUPPLIER_COUNTRY, "GB",
                       VatConfig.VAT_NUMBER_VALIDATION, "EXTERNAL",
                       "org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");
    }

    // ---------------------------------------------------------------- not using VAT

    /**
     * The case the whole change exists for. Nothing uploaded, not registered: this tenant does
     * not use the plugin, so it is healthy and there is nothing to tell anybody.
     */
    @Test(groups = "fast")
    public void testTenantThatNeverConfiguredVatIsHealthy() {
        final VatTenantStatus status =
                VatTenantStatus.of(new FakeHandler(defaults(), Boolean.FALSE, null), TENANT);

        assertEquals(status.getMode(), VatTenantStatus.Mode.NOT_CONFIGURED);
        assertTrue(status.isHealthy(), "a tenant that does not charge VAT is not unhealthy");
        assertTrue(status.getProblems().isEmpty(), "nothing to report: " + status.getProblems());
        assertFalse(status.isActive());
    }

    /**
     * The specific false statement that used to be made: no rates are configured because none
     * were wanted, not because anything is wrong.
     */
    @Test(groups = "fast")
    public void testUnconfiguredTenantIsNotToldItsRatesAreMissing() {
        final VatTenantStatus status =
                VatTenantStatus.of(new FakeHandler(defaults(), Boolean.FALSE, null), TENANT);

        assertFalse(String.join(" | ", status.getProblems()).contains("No VAT rates are configured"));
    }

    /**
     * And the hint that would have had them switch on a tax they do not charge. Registering the
     * plugin is only advice for a tenant that has asked for VAT.
     */
    @Test(groups = "fast")
    public void testUnconfiguredTenantIsNotToldToRegisterThePlugin() {
        final VatTenantStatus status =
                VatTenantStatus.of(new FakeHandler(defaults(), Boolean.FALSE, null), TENANT);

        assertFalse(String.join(" | ", status.getProblems()).contains("NOT listed in the tenant's"));
    }

    /** The message has to say why nothing is happening, or it reads as an unexplained silence. */
    @Test(groups = "fast")
    public void testUnconfiguredTenantSaysVatIsOptional() {
        final VatTenantStatus status =
                VatTenantStatus.of(new FakeHandler(defaults(), Boolean.FALSE, null), TENANT);

        assertTrue(status.getMessage().contains("VAT is optional"), status.getMessage());
    }

    // ---------------------------------------------------------------- switched off

    /**
     * Explicitly disabled after having been set up. No VAT is charged, so none of the taxation
     * diagnostics is true, and the tenant meant to do this.
     */
    @Test(groups = "fast")
    public void testDisabledTenantIsHealthyAndSilent() {
        final VatRuntime disabled = runtime(VatConfig.ENABLED, "false");
        final VatTenantStatus status = VatTenantStatus.of(
                new FakeHandler(disabled, Boolean.FALSE, "org.killbill.billing.plugin.vat.enabled=false"),
                TENANT);

        assertEquals(status.getMode(), VatTenantStatus.Mode.DISABLED);
        assertTrue(status.isHealthy(), "problems: " + status.getProblems());
        assertTrue(status.getProblems().isEmpty(), "problems: " + status.getProblems());
        assertFalse(status.isActive());
        assertTrue(status.getMessage().contains("switched off"), status.getMessage());
    }

    /**
     * Disabled is not a licence to stop reading the configuration. A key nothing reads is still a
     * key nothing reads, and it is exactly what would bite on the day VAT is switched on.
     */
    @Test(groups = "fast")
    public void testDisabledTenantStillReportsTypos() {
        final VatRuntime disabled = runtime(VatConfig.ENABLED, "false",
                                            "org.killbill.billing.plugin.vat.suplierCountry", "GB");
        final VatTenantStatus status = VatTenantStatus.of(
                new FakeHandler(disabled, Boolean.FALSE, "anything"), TENANT);

        assertEquals(status.getMode(), VatTenantStatus.Mode.DISABLED);
        assertFalse(status.isHealthy(), "a typo is a typo whether or not VAT is on");
        assertTrue(String.join(" | ", status.getProblems()).contains("suplierCountry"),
                   String.join(" | ", status.getProblems()));
    }

    /** Being registered is irrelevant once VAT is off: Kill Bill calls a plugin that adds nothing. */
    @Test(groups = "fast")
    public void testDisabledTenantThatIsStillRegisteredIsHealthy() {
        final VatRuntime disabled = runtime(VatConfig.ENABLED, "false");
        final VatTenantStatus status = VatTenantStatus.of(
                new FakeHandler(disabled, Boolean.TRUE, "anything"), TENANT);

        assertEquals(status.getMode(), VatTenantStatus.Mode.DISABLED);
        assertTrue(status.isHealthy());
    }

    // ---------------------------------------------------------------- using VAT

    /** A tenant that uploaded configuration has opted in, whether or not it registered yet. */
    @Test(groups = "fast")
    public void testConfiguredAndRegisteredTenantIsActiveAndHealthy() {
        final VatTenantStatus status = VatTenantStatus.of(
                new FakeHandler(workingUkConfig(), Boolean.TRUE, "anything"), TENANT);

        assertEquals(status.getMode(), VatTenantStatus.Mode.ACTIVE);
        assertTrue(status.isHealthy(), "problems: " + status.getProblems());
        assertTrue(status.isActive());
    }

    /**
     * The regression that started all of this: configured, healthy by its own account, and never
     * called by Kill Bill. This must stay red.
     */
    @Test(groups = "fast")
    public void testConfiguredButUnregisteredTenantIsUnhealthy() {
        final VatTenantStatus status = VatTenantStatus.of(
                new FakeHandler(workingUkConfig(), Boolean.FALSE, "anything"), TENANT);

        assertEquals(status.getMode(), VatTenantStatus.Mode.ACTIVE);
        assertFalse(status.isHealthy());
        assertTrue(String.join(" | ", status.getProblems()).contains("NOT listed in the tenant's"),
                   String.join(" | ", status.getProblems()));
    }

    /**
     * The mirror image: registered as an invoice plugin, so Kill Bill will call it, but nothing
     * was ever uploaded. That tenant wants VAT and is getting none, which the old code could not
     * distinguish from a tenant that wants nothing.
     */
    @Test(groups = "fast")
    public void testRegisteredWithoutConfigurationIsUnhealthy() {
        final VatTenantStatus status =
                VatTenantStatus.of(new FakeHandler(defaults(), Boolean.TRUE, null), TENANT);

        assertEquals(status.getMode(), VatTenantStatus.Mode.ACTIVE);
        assertFalse(status.isHealthy());
        assertTrue(String.join(" | ", status.getProblems()).contains("No VAT rates are configured"),
                   String.join(" | ", status.getProblems()));
    }

    /** An active tenant still gets the taxation diagnostics, which are the point of the plugin. */
    @Test(groups = "fast")
    public void testActiveTenantStillGetsTaxationWarnings() {
        final VatRuntime trusting = runtime(VatConfig.SUPPLIER_COUNTRY, "GB",
                                            VatConfig.VAT_NUMBER_VALIDATION, "NONE",
                                            "org.killbill.billing.plugin.vat.rates.GB.standard", "0.20");
        final VatTenantStatus status =
                VatTenantStatus.of(new FakeHandler(trusting, Boolean.TRUE, "anything"), TENANT);

        assertFalse(status.isHealthy());
        assertTrue(String.join(" | ", status.getProblems()).contains("vatNumberValidation is NONE"),
                   String.join(" | ", status.getProblems()));
    }

    // ---------------------------------------------------------------- edges

    /**
     * A failed tenant read is not an answer. Reporting the tenant unhealthy on the strength of a
     * question the plugin could not ask is the same false red this class exists to remove, so it
     * stays healthy and says the answer is not confirmed.
     */
    @Test(groups = "fast")
    public void testUnreadableRegistrationIsHealthyButSaysSo() {
        final VatTenantStatus status = VatTenantStatus.of(new FakeHandler(defaults(), null, null), TENANT);

        assertEquals(status.getMode(), VatTenantStatus.Mode.NOT_CONFIGURED);
        assertTrue(status.isHealthy());
        assertTrue(status.getMessage().contains("could not be read"), status.getMessage());
        assertNull(status.getRegisteredAsInvoicePlugin());
    }

    /**
     * An unreadable registration must not hide a tenant that has plainly opted in: the uploaded
     * configuration is intent on its own, so the diagnostics still run.
     */
    @Test(groups = "fast")
    public void testUnreadableRegistrationStillActivatesAConfiguredTenant() {
        final VatTenantStatus status =
                VatTenantStatus.of(new FakeHandler(defaults(), null, "anything"), TENANT);

        assertEquals(status.getMode(), VatTenantStatus.Mode.ACTIVE);
        assertFalse(status.isHealthy());
        // No registration hint, because whether it is registered is exactly what is not known.
        assertFalse(String.join(" | ", status.getProblems()).contains("NOT listed in the tenant's"),
                    String.join(" | ", status.getProblems()));
    }

    /** No tenant on the request: the plugin can only say that it is running. */
    @Test(groups = "fast")
    public void testNoTenantIsHealthy() {
        final VatTenantStatus status = VatTenantStatus.of(new FakeHandler(defaults(), Boolean.TRUE, "x"), null);

        assertTrue(status.isHealthy());
        assertTrue(status.getProblems().isEmpty());
        assertTrue(status.getMessage().contains("is running"), status.getMessage());
    }

    /**
     * No configurable at all is a plugin fault, not a tenant choice, and must not be swallowed by
     * the optional-VAT path.
     */
    @Test(groups = "fast")
    public void testMissingRuntimeIsUnhealthy() {
        final VatTenantStatus status = VatTenantStatus.of(new FakeHandler(null, Boolean.TRUE, "x"), TENANT);

        assertEquals(status.getMode(), VatTenantStatus.Mode.UNAVAILABLE);
        assertFalse(status.isHealthy());
    }
}
