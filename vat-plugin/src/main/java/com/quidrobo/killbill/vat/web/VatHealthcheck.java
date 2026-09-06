/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.killbill.billing.osgi.api.Healthcheck;
import org.killbill.billing.tenant.api.Tenant;

import com.quidrobo.killbill.vat.core.VatConfigurationHandler;
import com.quidrobo.killbill.vat.core.VatRuntime;

/**
 * Reports the plugin unhealthy when its configuration has problems.
 *
 * A tax plugin that is running but misconfigured is not healthy in any sense that matters, so
 * anything {@code VatConfig} flags as a problem fails the check. That makes a bad upload visible
 * to monitoring rather than to an accountant.
 *
 * With no tenant on the request the most that can honestly be said is that the plugin is running,
 * which is the same distinction the AvaTax plugin draws.
 */
public class VatHealthcheck implements Healthcheck {

    private final VatConfigurationHandler configurationHandler;

    public VatHealthcheck(final VatConfigurationHandler configurationHandler) {
        this.configurationHandler = configurationHandler;
    }

    @Override
    public HealthStatus getHealthStatus(final Tenant tenant, final Map properties) {
        if (tenant == null) {
            return HealthStatus.healthy("killbill-vat is running");
        }

        final VatRuntime runtime = configurationHandler.getRuntime(tenant.getId());
        if (runtime == null) {
            return HealthStatus.unHealthy("No killbill-vat configuration for this tenant");
        }

        final List<String> problems = runtime.getConfig().getProblems();
        if (problems.isEmpty()) {
            return HealthStatus.healthy("killbill-vat configured for "
                                        + runtime.getConfig().getSupplierCountry());
        }

        final Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("message", problems.size() + " configuration problem(s)");
        details.put("problems", problems);
        return new HealthStatus(false, details);
    }
}
