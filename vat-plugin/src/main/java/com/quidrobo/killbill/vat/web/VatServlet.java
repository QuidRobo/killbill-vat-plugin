/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.killbill.billing.plugin.core.PluginServlet;
import org.killbill.billing.tenant.api.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quidrobo.killbill.vat.calc.VatComputation;
import com.quidrobo.killbill.vat.core.PriceMode;
import com.quidrobo.killbill.vat.core.VatConfig;
import com.quidrobo.killbill.vat.core.VatConfigurationHandler;
import com.quidrobo.killbill.vat.core.VatRuntime;
import com.quidrobo.killbill.vat.rates.VatRate;
import com.quidrobo.killbill.vat.resolve.VatTreatment;
import com.quidrobo.killbill.vat.resolve.VatTreatmentRequest;
import com.quidrobo.killbill.vat.vies.VatNumberFormat;

/**
 * Makes the plugin's configuration and its decisions visible without generating an invoice.
 *
 * Kill Bill plugin configuration is uploaded and then invisible: a typo in a property key is not
 * an error, it is a key nobody reads, and every supply quietly becomes outside scope. For a tax
 * plugin that failure mode shows up as money at quarter end rather than as an error at deploy
 * time. These endpoints exist so that cannot happen.
 *
 * Mounted at {@code /plugins/killbill-vat/} by Kill Bill's servlet router, which keys on the
 * {@code PLUGIN_NAME_PROP} the activator registers with.
 *
 * <ul>
 *   <li>{@code GET /config} the effective configuration, plus everything wrong with it</li>
 *   <li>{@code GET /simulate?country=DE&vatNumber=...} what would be charged, and why</li>
 *   <li>{@code GET /healthcheck} usable by monitoring</li>
 * </ul>
 *
 * All three are read-only and change nothing.
 */
public class VatServlet extends PluginServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(VatServlet.class);

    private final VatConfigurationHandler configurationHandler;
    private final ObjectMapper mapper = new ObjectMapper();

    public VatServlet(final VatConfigurationHandler configurationHandler) {
        this.configurationHandler = configurationHandler;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        final Tenant tenant = getTenant(req);
        final UUID tenantId = tenant == null ? null : tenant.getId();

        try {
            if (path.startsWith("/config")) {
                writeJson(resp, configReport(tenantId));
            } else if (path.startsWith("/simulate")) {
                writeJson(resp, simulate(req, tenantId));
            } else if (path.startsWith("/healthcheck")) {
                final Map<String, Object> body = healthReport(tenantId);
                final boolean healthy = Boolean.TRUE.equals(body.get("healthy"));
                buildResponse(healthy ? HttpServletResponse.SC_OK
                                      : HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                              mapper.writeValueAsBytes(body), resp);
                setJsonContentType(resp);
            } else {
                buildNotFoundResponse("Unknown path " + path
                                      + ". Try /config, /simulate or /healthcheck.", resp);
            }
        } catch (final RuntimeException e) {
            logger.warn("VAT plugin servlet failed for path {}", path, e);
            final Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("error", e.getClass().getSimpleName());
            error.put("message", String.valueOf(e.getMessage()));
            buildResponse(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                          mapper.writeValueAsBytes(error), resp);
            setJsonContentType(resp);
        }
    }

    // ------------------------------------------------------------------ /config

    private Map<String, Object> configReport(final UUID tenantId) {
        final VatRuntime runtime = configurationHandler.getRuntime(tenantId);
        final Map<String, Object> out = new LinkedHashMap<String, Object>();

        if (runtime == null) {
            out.put("configured", Boolean.FALSE);
            out.put("problems", List.of("No configuration has been uploaded for this tenant."
                                        + " POST a .properties payload to"
                                        + " /1.0/kb/tenants/uploadPluginConfig/killbill-vat"));
            return out;
        }

        final VatConfig config = runtime.getConfig();
        out.put("configured", Boolean.TRUE);
        out.put("enabled", config.isEnabled());
        out.put("supplierCountry", config.getSupplierCountry());
        out.put("defaultPriceMode", config.getDefaultPriceMode().name());
        out.put("roundingScale", config.getRoundingScale());
        out.put("roundingMode", config.getRoundingMode().name());
        out.put("euB2cTreatment", config.getEuB2cTreatment().name());
        out.put("ossRegistered", config.isOssRegistered());
        out.put("euB2cUnregisteredTreatment", config.getEuB2cUnregisteredTreatment().name());
        out.put("unknownCountryTreatment", config.getUnknownCountryTreatment().name());
        out.put("vatNumberValidation", config.getVatNumberValidation().name());
        out.put("validationMaxAgeDays", config.getValidationMaxAgeDays());
        out.put("treatmentResolver", runtime.getResolver().getClass().getName());

        final List<Map<String, Object>> rates = new ArrayList<Map<String, Object>>();
        for (final VatRate rate : config.getRateTable().all()) {
            final Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("jurisdiction", rate.getJurisdiction());
            r.put("kind", rate.getKind());
            r.put("rate", rate.getRate());
            r.put("percent", rate.toPercentLabel());
            r.put("validFrom", rate.getValidFrom() == null ? null : rate.getValidFrom().toString());
            r.put("validTo", rate.getValidTo() == null ? null : rate.getValidTo().toString());
            rates.add(r);
        }
        out.put("rates", rates);

        // The point of the whole endpoint: what is wrong, in words, before it costs money.
        out.put("problems", config.getProblems());
        return out;
    }

    // ------------------------------------------------------------------ /simulate

    /**
     * Answers "what would you charge this customer, and why" without touching an invoice.
     *
     * Every parameter is optional so the endpoint is usable by hand from a browser or curl.
     * The reason string is the same one the calculator records, so what you see here is what the
     * invoice run would have decided.
     */
    private Map<String, Object> simulate(final HttpServletRequest req, final UUID tenantId) {
        final VatRuntime runtime = configurationHandler.getRuntime(tenantId);
        final Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (runtime == null) {
            out.put("error", "No configuration has been uploaded for this tenant.");
            return out;
        }
        final VatConfig config = runtime.getConfig();

        final String country = param(req, "country", null);
        final String rawVatNumber = param(req, "vatNumber", null);
        final boolean validated = Boolean.parseBoolean(param(req, "validated", "false"));
        final String planName = param(req, "plan", null);
        final String productName = param(req, "product", null);
        final BigDecimal amount = decimal(param(req, "amount", "100.00"));
        final LocalDate taxPoint = date(param(req, "date", null));

        final String vatNumber = VatNumberFormat.normalise(rawVatNumber);
        final VatTreatmentRequest request = VatTreatmentRequest.builder()
                                                               .customerCountry(country)
                                                               .customerVatNumber(vatNumber)
                                                               .customerVatNumberValidated(validated)
                                                               .business(vatNumber != null)
                                                               .planName(planName)
                                                               .productName(productName)
                                                               .taxPoint(taxPoint)
                                                               .build();

        final VatTreatment treatment = runtime.getResolver().resolve(request);
        final PriceMode priceMode = config.getPriceMode(planName, productName);
        final VatComputation.VatSplit split = VatComputation.split(amount, treatment.getRate(),
                                                                   priceMode,
                                                                   config.getRoundingScale(),
                                                                   config.getRoundingMode());

        final Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("country", country);
        input.put("vatNumber", vatNumber);
        input.put("vatNumberPlausible", vatNumber == null ? null : VatNumberFormat.isPlausible(vatNumber));
        input.put("vatNumberValidated", validated);
        input.put("plan", planName);
        input.put("product", productName);
        input.put("taxPoint", taxPoint == null ? null : taxPoint.toString());
        input.put("catalogueAmount", amount);
        input.put("priceMode", priceMode.name());
        out.put("input", input);

        final Map<String, Object> decision = new LinkedHashMap<String, Object>();
        decision.put("treatment", treatment.getKind().name());
        decision.put("jurisdiction", treatment.getJurisdiction());
        decision.put("rate", treatment.getRate());
        decision.put("taxItemDescription", treatment.getDescription());
        decision.put("invoiceLegend", treatment.getLegend());
        decision.put("reason", treatment.getReason());
        out.put("decision", decision);

        final Map<String, Object> amounts = new LinkedHashMap<String, Object>();
        amounts.put("net", split.getNet());
        amounts.put("vat", split.getVat());
        amounts.put("gross", split.getGross());
        amounts.put("chargeLineRewritten", split.requiresRewrite(amount));
        out.put("amounts", amounts);

        return out;
    }

    // ------------------------------------------------------------------ /healthcheck

    private Map<String, Object> healthReport(final UUID tenantId) {
        final Map<String, Object> out = new LinkedHashMap<String, Object>();
        final VatRuntime runtime = configurationHandler.getRuntime(tenantId);

        if (tenantId == null) {
            // No tenant on the request: the most we can say is that the plugin is running.
            out.put("healthy", Boolean.TRUE);
            out.put("message", "killbill-vat is running. Authenticate with a tenant to validate its"
                               + " configuration.");
            return out;
        }
        if (runtime == null) {
            out.put("healthy", Boolean.FALSE);
            out.put("message", "No configuration uploaded for this tenant.");
            return out;
        }

        final List<String> problems = runtime.getConfig().getProblems();
        out.put("healthy", problems.isEmpty());
        out.put("enabled", runtime.getConfig().isEnabled());
        out.put("supplierCountry", runtime.getConfig().getSupplierCountry());
        out.put("rateCount", runtime.getConfig().getRateTable().all().size());
        out.put("problems", problems);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private void writeJson(final HttpServletResponse resp, final Object body) throws IOException {
        setJsonContentType(resp);
        buildOKResponse(mapper.writeValueAsBytes(body), resp);
    }

    private static String param(final HttpServletRequest req, final String name, final String fallback) {
        final String value = req.getParameter(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static BigDecimal decimal(final String value) {
        try {
            return new BigDecimal(value);
        } catch (final RuntimeException e) {
            return new BigDecimal("100.00");
        }
    }

    private static LocalDate date(final String value) {
        if (value == null) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(value);
        } catch (final DateTimeParseException e) {
            return LocalDate.now();
        }
    }
}
