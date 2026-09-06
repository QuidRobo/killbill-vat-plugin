/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.core;

import com.quidrobo.killbill.vat.rates.VatRateSource;
import com.quidrobo.killbill.vat.resolve.DigitalServicesVatResolver;
import com.quidrobo.killbill.vat.resolve.VatTreatmentResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One tenant's configuration, rates and resolver, assembled together.
 *
 * Kept immutable and rebuilt wholesale when the tenant's config changes, so an invoice run never
 * sees a half-applied configuration.
 */
public final class VatRuntime {

    private static final Logger logger = LoggerFactory.getLogger(VatRuntime.class);

    private final VatConfig config;
    private final VatRateSource rateSource;
    private final VatTreatmentResolver resolver;

    public VatRuntime(final VatConfig config) {
        this.config = config;
        this.rateSource = config.getRateTable();
        this.resolver = instantiateResolver(config);
        this.resolver.init(config, rateSource);

        if (config.getRateTable().all().isEmpty()) {
            logger.warn("No VAT rates are configured. Every supply will resolve to OUTSIDE_SCOPE"
                        + " until rates are uploaded. See docs/CONFIGURATION.md");
        } else {
            logger.info("VAT plugin configured: supplier={} priceMode={} rates={}",
                        config.getSupplierCountry(), config.getDefaultPriceMode(),
                        config.getRateTable().all());
        }
    }

    public VatConfig getConfig() {
        return config;
    }

    public VatTreatmentResolver getResolver() {
        return resolver;
    }

    /**
     * A misconfigured resolver class falls back to the default rather than leaving the plugin
     * dead, because failing to load a custom class must not silently stop VAT being charged.
     */
    private static VatTreatmentResolver instantiateResolver(final VatConfig config) {
        final String className = config.getTreatmentResolverClass();
        if (className == null || className.trim().isEmpty()) {
            return new DigitalServicesVatResolver();
        }
        try {
            final Class<?> clazz = Class.forName(className.trim(), true,
                                                 VatRuntime.class.getClassLoader());
            return (VatTreatmentResolver) clazz.getDeclaredConstructor().newInstance();
        } catch (final Exception e) {
            logger.error("Unable to load the configured VAT treatment resolver {}. Falling back to {}",
                         className, DigitalServicesVatResolver.class.getName(), e);
            return new DigitalServicesVatResolver();
        }
    }
}
