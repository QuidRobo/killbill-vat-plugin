/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.core;

import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.plugin.api.PluginTenantContext;
import org.killbill.billing.plugin.api.notification.PluginTenantConfigurableConfigurationHandler;

/**
 * Reads per-tenant configuration from the tenant key {@code PLUGIN_CONFIG_killbill-vat}.
 *
 * Upload it with:
 * <pre>
 * POST /1.0/kb/tenants/uploadPluginConfig/killbill-vat
 * Content-Type: text/plain
 * </pre>
 *
 * Kill Bill fires {@code TENANT_CONFIG_CHANGE} on upload, which
 * {@code PluginConfigurationEventHandler} turns into a rebuild here, so rates and price modes
 * take effect without a restart.
 *
 * The name in that key is the one the activator registers, NOT the directory the jar happens to
 * sit in. Those differ whenever a plugin is installed under its artifact name
 * ({@code killbill-vat-plugin}) rather than its plugin name ({@code killbill-vat}), and Kill
 * Bill's own documentation describes the upload path segment as "the name on the filesystem",
 * which points at the wrong one. Config uploaded under the directory name lands in a key nothing
 * reads, and the plugin then runs on defaults: no rates, so every supply resolves to
 * OUTSIDE_SCOPE at 0%, silently. {@link #getConfigKeyName()} and
 * {@link #getRawTenantConfiguration} exist so {@code GET /config} can state plainly which key was
 * read and whether anything was found there, instead of leaving it to be inferred.
 */
public class VatConfigurationHandler extends PluginTenantConfigurableConfigurationHandler<VatRuntime> {

    private final String configKeyName;
    private final OSGIKillbillAPI killbillAPI;

    public VatConfigurationHandler(final String pluginName, final OSGIKillbillAPI osgiKillbillAPI) {
        super(pluginName, osgiKillbillAPI);
        // Mirrors PluginConfigurationHandler, which builds the same string but keeps it private.
        this.configKeyName = "PLUGIN_CONFIG_" + pluginName;
        this.killbillAPI = osgiKillbillAPI;
    }

    @Override
    protected VatRuntime createConfigurable(final Properties properties) {
        return new VatRuntime(new VatConfig(properties));
    }

    /** Never null: falls back to the default configurable set at startup. */
    public VatRuntime getRuntime(final UUID kbTenantId) {
        return getConfigurable(kbTenantId);
    }

    /** The tenant key this plugin reads. Upload configuration under the name inside it. */
    public String getConfigKeyName() {
        return configKeyName;
    }

    /**
     * The raw text stored under {@link #getConfigKeyName()} for a tenant, or null when there is
     * none. Read live rather than from the cache, so it answers "is it uploaded" separately from
     * "did the plugin pick it up" — which are different failures with different fixes.
     */
    public String getRawTenantConfiguration(final UUID kbTenantId) {
        if (kbTenantId == null || killbillAPI == null) {
            return null;
        }
        try {
            final List<String> values = killbillAPI.getTenantUserApi()
                    .getTenantValuesForKey(configKeyName, new PluginTenantContext(null, kbTenantId));
            return values == null || values.isEmpty() ? null : values.get(0);
        } catch (final Exception e) {
            return null;
        }
    }
}
