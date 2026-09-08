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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(VatConfigurationHandler.class);

    /** Kill Bill's tenant key holding the per-tenant configuration JSON. */
    private static final String PER_TENANT_CONFIG_KEY = "PER_TENANT_CONFIG";

    /** The per-tenant property naming the invoice plugins Kill Bill will actually invoke. */
    public static final String INVOICE_PLUGIN_PROPERTY = "org.killbill.invoice.plugin";

    private final String pluginName;
    private final String configKeyName;
    private final OSGIKillbillAPI killbillAPI;

    public VatConfigurationHandler(final String pluginName, final OSGIKillbillAPI osgiKillbillAPI) {
        super(pluginName, osgiKillbillAPI);
        this.pluginName = pluginName;
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
     * Whether this plugin is registered in the tenant's {@code org.killbill.invoice.plugin} list.
     *
     * Kill Bill only calls {@code getAdditionalInvoiceItems} on invoice plugins named in that
     * per-tenant property. A plugin that is installed, started, healthy and correctly configured
     * but absent from that list is simply never asked to tax anything, and every invoice comes out
     * at 0% with nothing in the log to say why. The plugin's own {@code /simulate} keeps answering
     * correctly throughout, because it never goes near the invoice pipeline, so the two disagree
     * and the configuration looks fine.
     *
     * Null means the question could not be answered (no tenant, or the read failed), which is not
     * the same as false and must not be reported as one.
     */
    public Boolean isRegisteredAsInvoicePlugin(final UUID kbTenantId) {
        if (kbTenantId == null || killbillAPI == null) {
            return null;
        }
        final String raw;
        try {
            final List<String> values = killbillAPI.getTenantUserApi()
                    .getTenantValuesForKey(PER_TENANT_CONFIG_KEY, new PluginTenantContext(null, kbTenantId));
            raw = values == null || values.isEmpty() ? null : values.get(0);
        } catch (final Exception e) {
            // "The read failed" is not "not registered". Reporting the latter puts a hint on
            // /config telling the operator to POST a per-tenant config, and that endpoint REPLACES
            // the whole thing: a spurious hint invites them to wipe properties that are already
            // there. Unknown stays unknown.
            logger.warn("Could not read {} for tenant {}", PER_TENANT_CONFIG_KEY, kbTenantId, e);
            return null;
        }
        return registrationFrom(raw, pluginName);
    }

    /**
     * Whether {@code pluginName} appears in the invoice plugin list of a per-tenant config payload.
     *
     * <p>Null {@code rawPerTenantConfig} means the read succeeded and there is no per-tenant config
     * at all, which is a definite "not registered": Kill Bill calls no invoice plugin that is not
     * named in {@code org.killbill.invoice.plugin}.
     */
    static Boolean registrationFrom(final String rawPerTenantConfig, final String pluginName) {
        if (rawPerTenantConfig == null) {
            return Boolean.FALSE;
        }
        final String registered = extractJsonStringValue(rawPerTenantConfig, INVOICE_PLUGIN_PROPERTY);
        if (registered == null) {
            return Boolean.FALSE;
        }
        for (final String name : registered.split(",")) {
            if (pluginName.equals(name.trim())) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /**
     * Pulls one string value out of the per-tenant config JSON without adding a JSON parser to
     * this bundle. The payload is a flat object of string properties, so this is enough, and a
     * malformed payload yields null rather than an exception on the invoice path.
     */
    static String extractJsonStringValue(final String json, final String key) {
        final String needle = "\"" + key + "\"";
        final int keyAt = json.indexOf(needle);
        if (keyAt < 0) {
            return null;
        }
        final int colon = json.indexOf(':', keyAt + needle.length());
        if (colon < 0) {
            return null;
        }
        final int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        final int close = json.indexOf('"', open + 1);
        if (close < 0) {
            return null;
        }
        return json.substring(open + 1, close);
    }

    /**
     * The raw text stored under {@link #getConfigKeyName()} for a tenant, or null when there is
     * none. Read live rather than from the cache, so it answers "is it uploaded" separately from
     * "did the plugin pick it up" — which are different failures with different fixes.
     */
    public String getRawTenantConfiguration(final UUID kbTenantId) {
        return readTenantValue(configKeyName, kbTenantId);
    }

    private String readTenantValue(final String key, final UUID kbTenantId) {
        if (kbTenantId == null || killbillAPI == null) {
            return null;
        }
        try {
            final List<String> values = killbillAPI.getTenantUserApi()
                    .getTenantValuesForKey(key, new PluginTenantContext(null, kbTenantId));
            return values == null || values.isEmpty() ? null : values.get(0);
        } catch (final Exception e) {
            return null;
        }
    }
}
