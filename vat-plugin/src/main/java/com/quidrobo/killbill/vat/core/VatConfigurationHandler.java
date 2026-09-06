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

import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
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
 */
public class VatConfigurationHandler extends PluginTenantConfigurableConfigurationHandler<VatRuntime> {

    public VatConfigurationHandler(final String pluginName, final OSGIKillbillAPI osgiKillbillAPI) {
        super(pluginName, osgiKillbillAPI);
    }

    @Override
    protected VatRuntime createConfigurable(final Properties properties) {
        return new VatRuntime(new VatConfig(properties));
    }

    /** Never null: falls back to the default configurable set at startup. */
    public VatRuntime getRuntime(final UUID kbTenantId) {
        return getConfigurable(kbTenantId);
    }
}
