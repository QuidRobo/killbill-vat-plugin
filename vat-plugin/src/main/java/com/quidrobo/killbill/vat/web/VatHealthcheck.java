/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.killbill.billing.osgi.api.Healthcheck;
import org.killbill.billing.tenant.api.Tenant;

import com.quidrobo.killbill.vat.core.VatConfigurationHandler;
import com.quidrobo.killbill.vat.core.VatTenantStatus;

/**
 * Reports the plugin unhealthy when a tenant that wants VAT has something wrong with it.
 *
 * A tax plugin that is running but misconfigured is not healthy in any sense that matters, so
 * anything {@code VatConfig} flags as a problem fails the check. That makes a bad upload visible
 * to monitoring rather than to an accountant.
 *
 * The qualifier matters as much as the rule. VAT is optional and this plugin is installed once
 * for a multi tenanted server, so most tenants may never use it; failing them for not having
 * configured a feature they did not ask for would make the check useless in exactly the
 * deployment it is meant to protect. {@link VatTenantStatus} draws that line, and draws it in one
 * place so this service and the plugin's own {@code /healthcheck} endpoint cannot disagree.
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

        final VatTenantStatus status = VatTenantStatus.of(configurationHandler, tenant.getId());
        if (status.isHealthy()) {
            return HealthStatus.healthy(status.getMessage());
        }

        final Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("message", status.getMessage());
        details.put("mode", status.getMode().name());
        if (!status.getProblems().isEmpty()) {
            details.put("problems", status.getProblems());
        }
        return new HealthStatus(false, details);
    }
}
