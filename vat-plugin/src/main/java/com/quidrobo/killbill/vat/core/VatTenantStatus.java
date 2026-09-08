/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Whether a tenant is using this plugin at all, and if so what is wrong with how it is set up.
 *
 * <p>VAT is optional. A business that is not VAT registered, or that is registered but bills
 * through a different mechanism, is a perfectly ordinary Kill Bill tenant, and the presence of
 * this plugin in the deployment says nothing about whether any given tenant wants it. The plugin
 * is installed once for the whole server and Kill Bill is multi tenanted, so "installed" and
 * "wanted by this tenant" are entirely different questions.
 *
 * <p>Getting that wrong is not cosmetic. Every diagnostic this plugin emits was written for a
 * tenant that is charging VAT, where "no rates are configured" means money is quietly going
 * uncollected. Emitted at a tenant that never asked for VAT, the same sentence is simply false,
 * it turns the healthcheck red, and it hands the operator instructions to register an invoice
 * plugin they do not want. That is the bug this class exists to prevent, and it is stated once
 * here rather than twice because the servlet and the Kill Bill {@code Healthcheck} service both
 * answer the same question and must never answer it differently.
 *
 * <p>Opting in is deliberately read from what the operator did, not from what parsed successfully.
 * Uploading configuration under {@code PLUGIN_CONFIG_<pluginName>} or naming the plugin in
 * {@code org.killbill.invoice.plugin} are both unambiguous acts of intent; having a usable rate
 * table is not, because a tenant whose rate keys are all misspelt has intent and no rates, and
 * that is precisely the case the diagnostics must still catch.
 */
public final class VatTenantStatus {

    /** What this tenant has asked the plugin to do. */
    public enum Mode {
        /**
         * Nothing was uploaded and the plugin is not registered for this tenant: it is not being
         * used here. No VAT is added and none was wanted, so there is nothing to report.
         */
        NOT_CONFIGURED,

        /** Opted in at some point, then switched off with {@code enabled=false}. */
        DISABLED,

        /** Opted in and switched on. Every diagnostic applies. */
        ACTIVE,

        /**
         * The plugin holds no configurable at all for this tenant, not even the startup default.
         * That is a fault in the plugin rather than a choice by the tenant.
         */
        UNAVAILABLE
    }

    private final Mode mode;
    private final boolean healthy;
    private final String message;
    private final List<String> problems;
    private final Boolean registeredAsInvoicePlugin;

    private VatTenantStatus(final Mode mode,
                            final boolean healthy,
                            final String message,
                            final List<String> problems,
                            final Boolean registeredAsInvoicePlugin) {
        this.mode = mode;
        this.healthy = healthy;
        this.message = message;
        this.problems = Collections.unmodifiableList(new ArrayList<String>(problems));
        this.registeredAsInvoicePlugin = registeredAsInvoicePlugin;
    }

    /**
     * Works out where a tenant stands, reading the two tenant keys that record intent.
     *
     * <p>Both reads go through Kill Bill's tenant cache, so this is cheap enough to call from a
     * healthcheck that monitoring polls. A tenant id of null means the caller is unauthenticated,
     * in which case neither key can be read and the only honest answer is that the plugin is
     * running: see {@link #ofUnknownTenant()}.
     */
    public static VatTenantStatus of(final VatConfigurationHandler handler, final UUID tenantId) {
        if (handler == null || tenantId == null) {
            return ofUnknownTenant();
        }

        final VatRuntime runtime = handler.getRuntime(tenantId);
        if (runtime == null) {
            return new VatTenantStatus(Mode.UNAVAILABLE, false,
                                       "No killbill-vat configuration is loaded for this tenant,"
                                       + " not even the startup default. The plugin did not"
                                       + " initialise correctly; check the plugin log.",
                                       List.<String>of(), null);
        }

        // Two independent acts of intent. Either one on its own means this tenant wants VAT, and
        // each is worth catching alone: configuration without registration is a plugin Kill Bill
        // never calls, and registration without configuration is a plugin with no rates.
        final Boolean registered = handler.isRegisteredAsInvoicePlugin(tenantId);
        final boolean tenantConfigPresent = handler.getRawTenantConfiguration(tenantId) != null;
        final boolean optedIn = Boolean.TRUE.equals(registered) || tenantConfigPresent;

        if (!optedIn) {
            // registered == null means the tenant API read failed rather than that the plugin is
            // absent from the list, and the configuration read went through the same API a moment
            // earlier, so neither answer can be relied on. Say so, and still report healthy: a
            // plugin must not fail a tenant on a question it was unable to ask, which is the same
            // false red this whole class exists to remove.
            final String inconclusive = registered == null
                    ? " The tenant's registration could not be read, so this is what the plugin"
                      + " can see rather than a confirmed answer; see the plugin log."
                    : "";
            return new VatTenantStatus(Mode.NOT_CONFIGURED, true,
                                       "VAT is not switched on for this tenant. Nothing is stored"
                                       + " under " + handler.getConfigKeyName() + " and the plugin"
                                       + " is not named in "
                                       + VatConfigurationHandler.INVOICE_PLUGIN_PROPERTY
                                       + ", so no VAT is added to any invoice. VAT is optional:"
                                       + " this is a valid state and needs no action." + inconclusive,
                                       List.<String>of(), registered);
        }

        final VatConfig config = runtime.getConfig();
        if (!config.isEnabled()) {
            // Only the problems that describe the uploaded text, because nothing here is going to
            // tax anything and every other diagnostic asserts that it will.
            final List<String> problems = config.getConfigurationProblems();
            return new VatTenantStatus(Mode.DISABLED, problems.isEmpty(),
                                       "VAT is switched off for this tenant (" + VatConfig.ENABLED
                                       + "=false), so no VAT is added to any invoice.",
                                       problems, registered);
        }

        final List<String> problems = new ArrayList<String>(config.getProblems());
        // Not a configuration problem in the plugin's own sense, but it has the same effect and is
        // worse: everything the plugin reports about itself looks correct while Kill Bill never
        // calls it. Only asked of a tenant that has opted in, because for one that has not, not
        // being registered is the point rather than a fault.
        if (Boolean.FALSE.equals(registered)) {
            problems.add(invoicePluginRegistrationHint());
        }
        return new VatTenantStatus(Mode.ACTIVE, problems.isEmpty(),
                                   problems.isEmpty()
                                   ? "killbill-vat is configured for " + config.getSupplierCountry()
                                   : problems.size() + " configuration problem(s)",
                                   problems, registered);
    }

    /** No tenant on the request, so the most that can honestly be said is that the plugin runs. */
    public static VatTenantStatus ofUnknownTenant() {
        return new VatTenantStatus(Mode.NOT_CONFIGURED, true,
                                   "killbill-vat is running. Authenticate with a tenant to"
                                   + " validate its configuration.",
                                   List.<String>of(), null);
    }

    /**
     * What to do about a plugin that is configured but that Kill Bill will never call.
     *
     * <p>Uploading plugin configuration and registering the plugin are two separate steps against
     * two separate endpoints, and only the first is discoverable from the plugin's own output.
     * Skipping the second produces invoices at 0% VAT with a plugin that reports itself perfectly
     * healthy.
     */
    public static String invoicePluginRegistrationHint() {
        return "This plugin is NOT listed in the tenant's "
               + VatConfigurationHandler.INVOICE_PLUGIN_PROPERTY + ", so Kill Bill never calls it"
               + " when building an invoice and every invoice comes out at 0% VAT. Register it"
               + " with POST /1.0/kb/tenants/uploadPerTenantConfig, Content-Type text/plain, body"
               + " {\"" + VatConfigurationHandler.INVOICE_PLUGIN_PROPERTY + "\":\"killbill-vat\"}."
               + " That is a DIFFERENT endpoint from uploadPluginConfig, and each call replaces the"
               + " whole per-tenant config, so include any properties already set there.";
    }

    public Mode getMode() {
        return mode;
    }

    /** False only when this tenant wants VAT and something about it is wrong. */
    public boolean isHealthy() {
        return healthy;
    }

    /** One line for a human, whether the news is good or bad. */
    public String getMessage() {
        return message;
    }

    /** Empty unless this tenant wants VAT. */
    public List<String> getProblems() {
        return problems;
    }

    /** TRUE, FALSE, or null when the question could not be answered. Never inferred. */
    public Boolean getRegisteredAsInvoicePlugin() {
        return registeredAsInvoicePlugin;
    }

    /** True when this tenant has asked for VAT and it is switched on. */
    public boolean isActive() {
        return mode == Mode.ACTIVE;
    }
}
