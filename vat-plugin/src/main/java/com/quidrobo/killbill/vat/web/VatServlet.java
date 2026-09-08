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
import com.quidrobo.killbill.vat.core.VatTenantStatus;
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
            // Exact match, not a prefix: /configuration used to be served as /config.
            final String route = path.endsWith("/") && path.length() > 1
                                 ? path.substring(0, path.length() - 1) : path;
            if ("/config".equals(route)) {
                writeJson(resp, configReport(tenantId));
            } else if ("/simulate".equals(route)) {
                writeJson(resp, simulate(req, tenantId));
            } else if ("/healthcheck".equals(route)) {
                final Map<String, Object> body = healthReport(tenantId);
                final boolean healthy = Boolean.TRUE.equals(body.get("healthy"));
                // Content type first: buildResponse can commit the response, after which setting
                // a header does nothing.
                setJsonContentType(resp);
                buildResponse(healthy ? HttpServletResponse.SC_OK
                                      : HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                              mapper.writeValueAsBytes(body), resp);
            } else {
                buildNotFoundResponse("Unknown path " + path
                                      + ". Try /config, /simulate or /healthcheck.", resp);
            }
        } catch (final RuntimeException e) {
            logger.warn("VAT plugin servlet failed for path {}", path, e);
            // Deliberately does not echo the exception message: it can quote the caller's own
            // input back, and the detail belongs in the log.
            final Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("error", "Request failed. See the plugin log for details.");
            setJsonContentType(resp);
            buildResponse(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                          mapper.writeValueAsBytes(error), resp);
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
        addTenantWarning(out, tenantId);

        // Where the configuration came from, stated rather than inferred. "Uploaded but the
        // plugin is running on defaults" and "never uploaded" look identical from the outside and
        // have completely different fixes: restart the plugin, versus upload under this key.
        final VatTenantStatus status = VatTenantStatus.of(configurationHandler, tenantId);
        if (tenantId != null) {
            // Deliberately omitted on a tenantless request. Nothing tenant-specific can be read
            // without credentials, and printing NOT_CONFIGURED there would answer a question about
            // the caller's tenant that was never asked, in the most alarming way possible.
            out.put("mode", status.getMode().name());
            out.put("modeExplanation", status.getMessage());
        }

        final String raw = configurationHandler.getRawTenantConfiguration(tenantId);
        out.put("configKey", configurationHandler.getConfigKeyName());
        out.put("tenantConfigFound", Boolean.valueOf(raw != null));
        if (raw == null && tenantId != null && status.isActive()) {
            // Only when the tenant is registered as an invoice plugin, which is what puts it in
            // ACTIVE with nothing uploaded. A tenant that has neither uploaded nor registered is
            // simply not using VAT, and telling it how to upload rates is answering a question
            // nobody asked.
            out.put("configHint", "Nothing is stored under '" + configurationHandler.getConfigKeyName()
                                  + "' for this tenant, so the plugin is running on defaults: no"
                                  + " rates, so every supply resolves to OUTSIDE_SCOPE at 0%."
                                  + " Upload with POST /1.0/kb/tenants/uploadPluginConfig/"
                                  + configurationHandler.getConfigKeyName()
                                          .substring("PLUGIN_CONFIG_".length())
                                  + " and note that is the PLUGIN name, which may differ from the"
                                  + " directory the jar is installed in.");
        } else if (raw != null && config.getRateTable().all().isEmpty() && config.isEnabled()) {
            out.put("configHint", "Configuration IS stored under '"
                                  + configurationHandler.getConfigKeyName() + "' but this plugin"
                                  + " is not using it: the tenant was configured before the upload"
                                  + " and cached. Restart the plugin to re-read it.");
        }

        addInvoicePluginRegistration(out, tenantId, status);

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
        addTenantWarning(out, tenantId);

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
        decision.put("customerVatNumber", treatment.getCustomerVatNumber());
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

        if (tenantId == null) {
            // No tenant on the request: the most we can say is that the plugin is running. This is
            // also the shape a container healthcheck sees, since it sends no Kill Bill API
            // credentials, so it must never depend on any tenant's configuration.
            out.put("healthy", Boolean.TRUE);
            out.put("message", VatTenantStatus.ofUnknownTenant().getMessage());
            return out;
        }

        final VatTenantStatus status = VatTenantStatus.of(configurationHandler, tenantId);
        out.put("healthy", Boolean.valueOf(status.isHealthy()));
        out.put("mode", status.getMode().name());
        out.put("message", status.getMessage());

        final VatRuntime runtime = configurationHandler.getRuntime(tenantId);
        if (runtime != null) {
            out.put("enabled", Boolean.valueOf(runtime.getConfig().isEnabled()));
            out.put("supplierCountry", runtime.getConfig().getSupplierCountry());
            out.put("rateCount", Integer.valueOf(runtime.getConfig().getRateTable().all().size()));
        }
        out.put("problems", status.getProblems());
        return out;
    }

    /**
     * Reports whether Kill Bill will actually call this plugin when it builds an invoice.
     *
     * Uploading plugin configuration and registering the plugin are two separate steps against two
     * separate endpoints, and only the first one is discoverable from the plugin's own output.
     * Skipping the second produces invoices at 0% VAT with a plugin that reports itself perfectly
     * healthy, so this states it rather than leaving it to be deduced.
     *
     * The hint is only attached when the tenant has actually asked for VAT. Not being registered
     * is the normal, correct state for a tenant that does not want the plugin, and telling that
     * operator to register it would be telling them to switch on a tax they do not charge.
     */
    private void addInvoicePluginRegistration(final Map<String, Object> out,
                                              final UUID tenantId,
                                              final VatTenantStatus status) {
        final Boolean registered = status.getRegisteredAsInvoicePlugin();
        if (registered == null) {
            return;
        }
        out.put("registeredAsInvoicePlugin", registered);
        if (!registered.booleanValue() && status.isActive()) {
            out.put("registrationHint", VatTenantStatus.invoicePluginRegistrationHint());
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Says loudly when a request resolved to no tenant.
     *
     * Kill Bill identifies the tenant from the API key and secret headers. Open one of these URLs
     * in a browser and there are none, so the configuration handler falls back to the default
     * configuration rather than the tenant's. Reporting that silently is worse than useless: it
     * shows rates and price modes that are not the ones invoicing will use, so an upload that
     * worked perfectly looks like it did nothing, and the obvious next move is to "fix" a
     * configuration that was never broken. Ask how I know.
     */
    private static void addTenantWarning(final Map<String, Object> out, final UUID tenantId) {
        if (tenantId != null) {
            out.put("tenantId", tenantId.toString());
            return;
        }
        out.put("tenantId", null);
        out.put("WARNING", "This request carried no tenant, so everything below is the DEFAULT"
                           + " configuration, NOT any tenant's uploaded configuration. Send the"
                           + " X-Killbill-ApiKey and X-Killbill-ApiSecret headers to see the"
                           + " configuration that invoicing will actually use.");
    }

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
