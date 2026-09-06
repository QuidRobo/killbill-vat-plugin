/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.rates;

import java.time.LocalDate;

/**
 * Where rates come from. Config-backed in v1, database-backed later, without the resolver caring.
 */
public interface VatRateSource {

    /**
     * @param jurisdiction ISO 3166-1 alpha-2
     * @param kind         "standard" unless you configure others
     * @param on           the date the rate must be in force on, normally the tax point
     * @return the applicable rate, or null when the jurisdiction or date is not covered
     */
    VatRate findRate(String jurisdiction, String kind, LocalDate on);

    /** True when any rate at all is configured for this jurisdiction. */
    boolean hasJurisdiction(String jurisdiction);
}
