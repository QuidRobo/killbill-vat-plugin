/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.formatter;

import java.util.Hashtable;

import org.killbill.billing.invoice.plugin.api.InvoiceFormatterFactory;
import org.killbill.billing.osgi.api.OSGIPluginProperties;
import org.killbill.billing.osgi.libs.killbill.KillbillActivatorBase;
import org.osgi.framework.BundleContext;

/**
 * Registers the formatter under a plugin name so Kill Bill can resolve it via
 * {@code org.killbill.template.invoiceFormatterFactoryPluginName}.
 *
 * The bundle activator is not declared in the pom: killbill-oss-parent tells bnd to find the class
 * extending {@link KillbillActivatorBase}, which is this one.
 */
public class VatInvoiceFormatterActivator extends KillbillActivatorBase {

    public static final String PLUGIN_NAME = "killbill-vat-formatter";

    @Override
    public void start(final BundleContext context) throws Exception {
        // Populates the protected killbillAPI field this activator passes to the factory.
        super.start(context);

        final InvoiceFormatterFactory factory = new VatInvoiceFormatterFactory(killbillAPI);

        final Hashtable<String, String> properties = new Hashtable<String, String>();
        properties.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, InvoiceFormatterFactory.class, factory, properties);
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        // KillbillActivatorBase unregisters everything the registrar holds.
        super.stop(context);
    }
}
