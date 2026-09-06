/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.quidrobo.killbill.vat.resolve;

import com.quidrobo.killbill.vat.core.VatConfig;
import com.quidrobo.killbill.vat.rates.VatRateSource;

/**
 * Decides how one supply is treated for VAT.
 *
 * This is the plugin's extension point, and the reason it is worth open sourcing. Multiplying by
 * a rate is trivial; deciding <em>which</em> rate applies to <em>this</em> customer is the work.
 * Anyone whose rules differ implements this interface and changes one config property:
 *
 * <pre>org.killbill.billing.plugin.vat.treatmentResolver = com.example.MyResolver</pre>
 *
 * Implementations need a public no-argument constructor and must be thread safe once
 * {@link #init} has returned.
 */
public interface VatTreatmentResolver {

    void init(VatConfig config, VatRateSource rateSource);

    /**
     * Never returns null. An unresolvable case must come back as
     * {@link VatTreatmentKind#OUTSIDE_SCOPE} carrying a reason, never as a silent zero rate,
     * so that the decision is auditable after the fact.
     */
    VatTreatment resolve(VatTreatmentRequest request);
}
