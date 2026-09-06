/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.core;

import java.util.Hashtable;
import java.util.Properties;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import org.killbill.billing.invoice.plugin.api.InvoicePluginApi;
import org.killbill.billing.osgi.api.Healthcheck;
import org.killbill.billing.osgi.api.OSGIPluginProperties;
import org.killbill.billing.osgi.libs.killbill.KillbillActivatorBase;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.clock.Clock;
import org.killbill.clock.DefaultClock;
import org.osgi.framework.BundleContext;

import com.quidrobo.killbill.vat.api.VatInvoicePluginApi;
import com.quidrobo.killbill.vat.web.VatHealthcheck;
import com.quidrobo.killbill.vat.web.VatServlet;

/**
 * Bundle activator.
 *
 * The {@code Bundle-Activator} header is not declared in the pom: killbill-oss-parent tells bnd
 * to find the class extending {@link KillbillActivatorBase}, which is this one.
 */
public class VatActivator extends KillbillActivatorBase {

    /** Also the tenant config key suffix: {@code PLUGIN_CONFIG_killbill-vat}. */
    public static final String PLUGIN_NAME = "killbill-vat";

    private VatConfigurationHandler configurationHandler;

    @Override
    public void start(final BundleContext context) throws Exception {
        // Populates killbillAPI, configProperties, registrar and dispatcher.
        super.start(context);

        configurationHandler = new VatConfigurationHandler(PLUGIN_NAME, killbillAPI);
        configurationHandler.setDefaultConfigurable(new VatRuntime(new VatConfig(defaultProperties())));

        // KillbillActivatorBase.clock is an OSGIKillbillClock, while PluginApi wants a Clock.
        final Clock pluginClock = new DefaultClock();

        final InvoicePluginApi invoicePluginApi =
                new VatInvoicePluginApi(configurationHandler, killbillAPI, configProperties, pluginClock);

        final Hashtable<String, String> properties = new Hashtable<String, String>();
        properties.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, InvoicePluginApi.class, invoicePluginApi, properties);

        // Lets monitoring, and other plugins, see that the configuration is sound.
        final Healthcheck healthcheck = new VatHealthcheck(configurationHandler);
        registrar.registerService(context, Healthcheck.class, healthcheck, properties);

        // Mounted at /plugins/killbill-vat/ by Kill Bill's servlet router, keyed on the plugin
        // name above. Serves /config, /simulate and /healthcheck, all read-only.
        final HttpServlet servlet = new VatServlet(configurationHandler);
        registrar.registerService(context, Servlet.class, servlet, properties);

        // Rebuilds the per-tenant runtime when configuration is uploaded, so rates and price
        // modes change without a restart.
        dispatcher.registerEventHandlers(new PluginConfigurationEventHandler(configurationHandler));
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        // Unregisters services and event handlers.
        super.stop(context);
    }

    /**
     * Global defaults from the Kill Bill system properties, used for tenants that have uploaded
     * no configuration of their own.
     */
    private Properties defaultProperties() {
        final Properties properties = new Properties();
        final Properties system = configProperties == null ? null : configProperties.getProperties();
        if (system != null) {
            for (final String key : system.stringPropertyNames()) {
                if (key.startsWith(VatConfig.PROPERTY_PREFIX)) {
                    properties.setProperty(key, system.getProperty(key));
                }
            }
        }
        return properties;
    }
}
