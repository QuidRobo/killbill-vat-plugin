/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.resolve;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.quidrobo.killbill.vat.core.VatConfig;
import com.quidrobo.killbill.vat.rates.VatRate;
import com.quidrobo.killbill.vat.rates.VatRateSource;

/**
 * The default resolver: UK and EU rules for electronically supplied services.
 *
 * The decision tree, in order:
 *
 * <ol>
 *   <li>No customer country on file, apply the configured {@code unknownCountryTreatment}.
 *       Defaults to DOMESTIC, deliberately. Under-collecting VAT is the expensive direction of
 *       this error: the supplier carries the liability, while over-collecting is a refund.</li>
 *   <li>Customer in the supplier's own country, DOMESTIC at the supplier's standard rate.
 *       This holds for both B2B and B2C: a UK business selling to a UK business still charges
 *       UK VAT, and the reverse charge does not apply domestically.</li>
 *   <li>Customer elsewhere with a validated VAT number, REVERSE_CHARGE at 0%.</li>
 *   <li>Customer in the EU without one, the configured {@code euB2cTreatment}, normally
 *       DESTINATION at that country's rate under OSS.</li>
 *   <li>Customer anywhere else: DESTINATION if a registration exists for that country,
 *       otherwise OUTSIDE_SCOPE.</li>
 * </ol>
 *
 * The deliberate asymmetry is at step 3. An <em>unvalidated</em> or invalid VAT number does not
 * become a reverse charge; it falls through to the B2C branch. Treating an unverified number as
 * proof of business status is how suppliers end up owing VAT they never collected.
 */
public class DigitalServicesVatResolver implements VatTreatmentResolver {

    private static final Logger logger = LoggerFactory.getLogger(DigitalServicesVatResolver.class);

    private VatConfig config;
    private VatRateSource rateSource;

    @Override
    public void init(final VatConfig config, final VatRateSource rateSource) {
        this.config = config;
        this.rateSource = rateSource;
    }

    @Override
    public VatTreatment resolve(final VatTreatmentRequest request) {
        final String supplierCountry = config.getSupplierCountry();
        final String customerCountry = request.getCustomerCountry();

        if (customerCountry == null) {
            return applyFallback(config.getUnknownCountryTreatment(), supplierCountry, request,
                                 "no country on the customer account");
        }

        if (supplierCountry.equalsIgnoreCase(customerCountry)) {
            return charge(supplierCountry, request,
                          "customer is in the supplier country " + supplierCountry);
        }

        if (hasUsableVatNumber(request)) {
            if (config.isReverseChargeCountry(customerCountry)) {
                return VatTreatment.reverseCharge(customerCountry,
                                                  request.getCustomerVatNumber(),
                                                  config.getReverseChargeLegend(),
                                                  "validated VAT number " + request.getCustomerVatNumber()
                                                  + " in " + customerCountry);
            }
            if (!config.isRegisteredIn(customerCountry)) {
                // A business customer outside the reverse charge area. The place of supply of a
                // B2B electronically supplied service is where the customer belongs, so this is
                // outside scope rather than a reverse charge. Both are 0%, but a reverse charge
                // legend on a non-EU invoice is simply wrong.
                return VatTreatment.outsideScope(customerCountry, config.getOutsideScopeLegend(),
                                                 "business customer in " + customerCountry
                                                 + ", outside the reverse charge area and with no"
                                                 + " registration there");
            }
        }

        if (config.isEuCountry(customerCountry)) {
            final String reason = request.getCustomerVatNumber() == null
                                  ? "EU customer with no VAT number, treated as B2C"
                                  : "EU customer whose VAT number is not validated, treated as B2C";

            if (config.getEuB2cTreatment() == VatTreatmentKind.DESTINATION && !config.isOssRegistered()) {
                // Having a rate for Germany is not authority to charge German VAT. Without an OSS
                // registration there is no mechanism to remit what would be collected, so fall
                // back rather than collect tax that cannot be handed over.
                logger.warn("EU B2C supply to {} would be charged at the destination rate, but"
                            + " {} is false. Applying {} instead. Register for OSS, or set"
                            + " euB2cUnregisteredTreatment deliberately.",
                            customerCountry, VatConfig.OSS_REGISTERED,
                            config.getEuB2cUnregisteredTreatment());
                return applyFallback(config.getEuB2cUnregisteredTreatment(), customerCountry, request,
                                     reason + ", but no OSS registration is configured");
            }
            return applyFallback(config.getEuB2cTreatment(), customerCountry, request, reason);
        }

        if (config.isRegisteredIn(customerCountry)) {
            return charge(customerCountry, request,
                          "supplier holds a registration in " + customerCountry);
        }

        return VatTreatment.outsideScope(customerCountry, config.getOutsideScopeLegend(),
                                         "no registration for " + customerCountry
                                         + " and the customer is outside " + supplierCountry);
    }

    /**
     * A VAT number counts only if it is present and the configured level of proof is satisfied.
     * The validation itself happens out of band; this only reads the verdict.
     */
    private boolean hasUsableVatNumber(final VatTreatmentRequest request) {
        final String number = request.getCustomerVatNumber();
        if (number == null || number.isEmpty()) {
            return false;
        }
        switch (config.getVatNumberValidation()) {
            case NONE:
                return true;
            case CHECKSUM:
            case EXTERNAL:
            default:
                return request.isCustomerVatNumberValidated();
        }
    }

    private VatTreatment applyFallback(final VatTreatmentKind kind,
                                       final String jurisdiction,
                                       final VatTreatmentRequest request,
                                       final String reason) {
        switch (kind) {
            case DOMESTIC:
                return charge(config.getSupplierCountry(), request, reason);
            case DESTINATION:
                return charge(jurisdiction, request, reason);
            case REVERSE_CHARGE:
                return VatTreatment.reverseCharge(jurisdiction, request.getCustomerVatNumber(),
                                                  config.getReverseChargeLegend(), reason);
            case EXEMPT:
                return VatTreatment.exempt(jurisdiction, config.getOutsideScopeLegend(), reason);
            case OUTSIDE_SCOPE:
            default:
                return VatTreatment.outsideScope(jurisdiction, config.getOutsideScopeLegend(), reason);
        }
    }

    /**
     * Charges the standard rate for a jurisdiction as at the tax point.
     *
     * A missing rate is not treated as zero. Falling back to 0% on a configuration gap would
     * silently under-collect for as long as nobody notices, so the supply falls to
     * OUTSIDE_SCOPE with a reason that names the gap, which shows up in the logs and on the
     * invoice rather than quietly costing money.
     */
    private VatTreatment charge(final String jurisdiction,
                                final VatTreatmentRequest request,
                                final String reason) {
        final VatRate rate = rateSource.findRate(jurisdiction, "standard", request.getTaxPoint());
        if (rate == null) {
            return VatTreatment.outsideScope(jurisdiction, config.getOutsideScopeLegend(),
                                             "no standard rate configured for " + jurisdiction
                                             + " on " + request.getTaxPoint() + " (" + reason + ")");
        }
        final VatTreatmentKind kind = jurisdiction.equalsIgnoreCase(config.getSupplierCountry())
                                      ? VatTreatmentKind.DOMESTIC
                                      : VatTreatmentKind.DESTINATION;
        return VatTreatment.charging(kind, jurisdiction, rate.getRate(),
                                     config.renderTaxItemDescription(rate.toPercentLabel(), jurisdiction),
                                     reason);
    }
}
