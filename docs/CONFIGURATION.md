# Configuration reference

All properties live under `org.killbill.billing.plugin.vat.` and are uploaded per tenant:

```
POST /1.0/kb/tenants/uploadPluginConfig/killbill-vat
Content-Type: text/plain
```

Kill Bill fires `TENANT_CONFIG_CHANGE` on upload, which rebuilds the tenant's runtime, so rates
and price modes change without a restart. Properties set as Kill Bill system properties act as
the default for tenants that have uploaded nothing.

---

## Before any of this: registering the plugin

Everything on this page configures **how** the plugin taxes. None of it makes Kill Bill *call* the
plugin. That is a separate per-tenant property, in a separate payload, at a separate endpoint:

```
POST /1.0/kb/tenants/uploadPerTenantConfig
Content-Type: text/plain

{"org.killbill.invoice.plugin":"killbill-vat"}
```

Kill Bill invokes `getAdditionalInvoiceItems` only on plugins named in that CSV list, in order.
Leave the plugin out of it and every invoice is 0% VAT, with no log line and no failed healthcheck
to point at it, while `/simulate` continues to return correct answers because it bypasses the
invoice pipeline entirely. It is the single most expensive way to misconfigure this plugin.

Each call replaces the whole per-tenant config, so send every property that tenant needs in one
payload:

```
{"org.killbill.invoice.plugin":"killbill-vat","org.killbill.payment.retry.days":"1,3,5"}
```

`GET /config` reports `registeredAsInvoicePlugin`, and `GET /healthcheck` returns 503 while it is
false.

---

## Core

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch. When false the plugin adds no items at all, and stops reporting problems that assert something about VAT being charged. Problems with the configuration text itself, such as a misspelt key or an unparseable rate, are still reported: they are true either way and are what would bite when VAT is switched back on. |
| `recordZeroVatItems` | `true` | Emit a zero-amount TAX item for supplies that carry no VAT (reverse charge, outside scope, zero rated). That item is what carries the treatment, the customer's country and their VAT number through to the invoice formatter, which on Kill Bill 0.24.x is handed no tenant context and can read nothing for itself. It is invisible on the invoice: line items are charges only and the VAT summary is gated on a non-zero total. A kill switch, not a preference: set it to false only if Kill Bill ever refuses a zero-amount tax item, and accept losing the treatment legend on zero-VAT supplies. |
| `supplierCountry` | `GB` | Where the supplier is established. Defines a domestic supply. |
| `treatmentResolver` | built-in | FQCN of a `VatTreatmentResolver`. Needs a public no-arg constructor. A class that fails to load falls back to the default rather than leaving VAT uncharged. |

## VAT-inclusive pricing

| Property | Default | Meaning |
|---|---|---|
| `priceMode` | `EXCLUSIVE` | `EXCLUSIVE` or `INCLUSIVE`. Also accepts `NET`/`GROSS`. |
| `priceMode.plans.<planName>` | | Per-plan override. |
| `priceMode.products.<productName>` | | Per-product override. |

Resolution order is **plan, then product, then tenant default**, so a catalogue can price
consumer plans inclusively and business plans exclusively.

### How inclusive mode works

Kill Bill treats catalogue amounts as net everywhere, so inclusive pricing is implemented by
returning the charge item **with the same id** and a rewritten amount. `InvoicePluginDispatcher`
classifies `amount` as a mutable field and treats a returned item whose id already exists as an
update rather than an addition.

```
catalogue 120.00, INCLUSIVE, 20%
  -> RECURRING item rewritten to 100.00   (same id, same type, same dates)
  -> TAX item        20.00                (linked to it)
  -> invoice total  120.00                (unchanged)
```

Two things to know before relying on it:

1. **The rewrite is first-generation only.** On a re-run against an already persisted invoice,
   `DefaultInvoiceDao` only applies amount changes for types in `ALLOWED_INVOICE_ITEM_TYPES`, and
   `RECURRING` is not in that list. An amount rewrite of an already-persisted recurring item is
   silently dropped.
2. **Items that already carry adjustments are not rewritten.** Changing the amount would silently
   change what those adjustments were computed against. The plugin logs a warning and charges VAT
   on top instead, which is visible rather than wrong.

No core Kill Bill test exercises the in-place amount rewrite. It follows from the field
classification and the `DefaultInvoiceDao` comment that explicitly anticipates plugins modifying
amounts, but verify it against your own server before production.

## Rounding

| Property | Default |
|---|---|
| `rounding.scale` | `2` |
| `rounding.mode` | `HALF_UP` |

VAT is computed as `gross - net` in inclusive mode, never as `gross * rate / (1 + rate)`.
Rounding both halves independently lets them disagree: at 20% on 9.99 that produces
8.33 + 1.67 = 10.00, one penny more than the advertised price.

`rounding.mode` accepts only **`HALF_UP`, `HALF_DOWN` and `HALF_EVEN`**. Anything else is
reported in `problems` and `HALF_UP` is used instead. The restriction is not fussiness: a mode
that is not symmetric about zero rounds a credit differently from the sale it reverses, so the
two never net to nothing and the difference accumulates on the VAT account forever. `FLOOR`,
`CEILING`, `UP` and `DOWN` are all asymmetric in that sense.

## Rates

Shorthand, always in force:

```properties
org.killbill.billing.plugin.vat.rates.GB.standard = 0.20
org.killbill.billing.plugin.vat.rates.DE.standard = 19%
```

Dated, for rate history. Half-open ranges: `from` is included, `to` is not.

```properties
org.killbill.billing.plugin.vat.rates.GB.standard.0.rate = 0.175
org.killbill.billing.plugin.vat.rates.GB.standard.0.from = 2008-12-01
org.killbill.billing.plugin.vat.rates.GB.standard.0.to   = 2010-01-01
org.killbill.billing.plugin.vat.rates.GB.standard.1.rate = 0.20
org.killbill.billing.plugin.vat.rates.GB.standard.1.from = 2011-01-04
```

Rates are looked up **as at the tax point**, not as at today, so a credit against an old supply
reverses VAT at the rate that applied then.

A bare `20` is **rejected**. It is ambiguous between 20% and 2000%, and silently reading it
either way is worse than refusing. Write `0.20` or `20%`.

A rate must be a fraction in `[0, 1)`. `1.20` (somebody who meant 20% and wrote the multiplier),
`100%` and a negative are all rejected and reported.

A missing rate resolves to `OUTSIDE_SCOPE` with a reason naming the gap, never to 0%.

### The `kind` dimension

The segment between the country and the value is the rate **kind**:
`rates.<CC>.<kind>` and `rates.<CC>.<kind>.<n>.rate|from|to`.

`standard` is the only kind the built-in resolver ever looks up, and it is the one every
diagnostic checks for. Other kinds (`reduced`, `zero`, `super-reduced`, whatever you name them)
can be configured and are returned by `GET /config`, but nothing selects them until a custom
`VatTreatmentResolver` asks `VatRateSource.findRate(country, kind, date)` for one. Kill Bill's
catalogue carries no tax-category field, so there is no automatic way to know that one plan is
reduced-rated and another is not; that mapping is exactly what a custom resolver is for.

The practical consequence is that a **misspelt kind is silent by nature**: `rates.GB.standrd`
registers a perfectly valid rate of a kind nobody asks for, and every domestic supply then
resolves to `OUTSIDE_SCOPE` at 0%. That is why `problems` checks for a `standard` rate in force
today rather than merely for the jurisdiction.

Keys under `rates.` that match neither shape (a three-letter country code, `…0.form` for `from`)
are reported rather than ignored.

## Treatment

| Property | Default | Meaning |
|---|---|---|
| `euCountries` | the 27 member states | Which countries count as EU. |
| `reverseChargeCountries` | same as `euCountries` | Where a B2B supply is a reverse charge rather than outside scope. An EU-established supplier should add `GB`. |
| `euB2cTreatment` | `DESTINATION` | What an EU consumer gets. `DESTINATION` charges their own country rate under OSS. |
| `ossRegistered` | `false` | Whether the supplier holds an EU One Stop Shop registration. Required before `DESTINATION` will actually charge an EU consumer. |
| `euB2cUnregisteredTreatment` | `DOMESTIC` | What an EU consumer gets while `ossRegistered` is false. |
| `unknownCountryTreatment` | `DOMESTIC` | What to do with no country on the account. |
| `registeredCountries` | empty | Non-EU countries where the supplier holds a registration. Until a country is listed, supplies there are outside scope. |

The three treatment properties (`euB2cTreatment`, `euB2cUnregisteredTreatment`,
`unknownCountryTreatment`) accept any of:

| Value | Effect |
|---|---|
| `DOMESTIC` | Charge the supplier country's standard rate. |
| `DESTINATION` | Charge the customer country's standard rate. Falls to `OUTSIDE_SCOPE` if none is configured. |
| `REVERSE_CHARGE` | 0%, with the reverse charge legend and the customer's VAT number. |
| `OUTSIDE_SCOPE` | 0%, place of supply elsewhere. |
| `EXEMPT` | 0%, but exempt rather than out of scope. |

`EXEMPT` and `OUTSIDE_SCOPE` are both 0% and are deliberately distinct: an exempt supply is
within the scope of VAT and is reported on a VAT return, an out-of-scope one is not, and the two
have different consequences for input tax recovery. Nothing in the plugin resolves to `EXEMPT` on
its own; it is there for a custom resolver, or as a deliberate fallback setting.

### The decision tree

```
no customer country          -> unknownCountryTreatment (default DOMESTIC)
customer in supplierCountry  -> DOMESTIC at the supplier standard rate
validated VAT number
    in reverseChargeCountries -> REVERSE_CHARGE at 0%, legend required
    elsewhere, no registration -> OUTSIDE_SCOPE
customer in the EU, no valid number -> euB2cTreatment (default DESTINATION),
                                        but only if ossRegistered; otherwise
                                        euB2cUnregisteredTreatment + a warning
registeredCountries contains it     -> DESTINATION at that country's rate
otherwise                            -> OUTSIDE_SCOPE
```

Three defaults are chosen deliberately and are worth understanding before you change them:

- **An unvalidated VAT number is not a reverse charge.** It falls through to the B2C branch and
  VAT is charged. The supplier carries the liability for getting this wrong.
- **An unknown country charges domestic VAT.** Over-collecting is a refund; under-collecting is a
  debt. Set `unknownCountryTreatment = OUTSIDE_SCOPE` only if you have a reason.
- **Reverse charge and outside scope are both 0% but are not the same.** A reverse charge legend
  on a non-EU invoice is simply wrong, so they are separate treatments with separate wording.
- **A configured rate is not authority to charge it.** Charging German VAT without an OSS
  registration means collecting tax with no mechanism to remit it, which is worse than not
  charging it. `ossRegistered` has to be turned on deliberately, and the fallback is logged.

## VAT number validation

| Property | Default |
|---|---|
| `vatNumberValidation` | `CHECKSUM` |
| `vatNumberValidation.maxAgeDays` | `90` |

| Mode | Behaviour |
|---|---|
| `NONE` | Trust whatever is on file. Development only. |
| `CHECKSUM` | Structure plus check digits, offline. Not proof of registration. |
| `EXTERNAL` | Requires `customerVatNumberValidatedAt` on the account, within `maxAgeDays`. **Use this in production.** |

Structural validation covers every EU country plus GB and XI. Check digits are implemented for
**GB, DE, IT, NL and FR**. Countries without an implemented checksum pass on structure alone
rather than being rejected, since VIES is the authority either way.

An all-zero body (`GB000000000`, `NL000000000B01`) is rejected outright, even though it satisfies
the national check digits. It is what gets typed to get past a required field.

**Where validation should live.** In `EXTERNAL` mode the plugin still applies the offline format
check as a cheap gate, but the decision rests on `customerVatNumberValidatedAt`: without a
recorded check inside `maxAgeDays`, VAT is charged rather than the reverse charge applied. That
is the intended production shape, because the system that owns the customer record is the only
layer that cannot be bypassed, and it is the one that can call VIES asynchronously rather than
inside an invoice run.

The plugin carries its own copy of the format and checksum rules for `CHECKSUM` mode, which exists
for adopters who have no such system in front of Kill Bill. If you do have one, put the real
validation there, set `EXTERNAL`, and treat the plugin's copy as unused.

Until the VIES client ships (roadmap item 2), `customerVatNumberValidatedAt` is written by
whatever external process you run. It accepts a bare date or a full ISO timestamp.

## Account custom fields

| Field | Purpose |
|---|---|
| `customerVatNumber` | The registration number, any format. Normalised before use. |
| `customerVatNumberValidatedAt` | ISO date of the last successful external check. |
| `customerTaxCountry` | Overrides the billing country for place of supply. |

```bash
curl -X POST ... -H 'Content-Type: application/json' \
  -d '[{"name":"customerVatNumber","value":"DE136695976"}]' \
  'http://127.0.0.1:8080/1.0/kb/accounts/<accountId>/customFields'
```

There is deliberately no `customerIsBusiness` field. Under both UK and EU rules what makes a
supply B2B for VAT purposes is a valid VAT registration number, not a self-declared status, and a
flag anyone can tick is not evidence a tax authority accepts. A customer with no number is
charged VAT whatever they call themselves.

Both fields are read by the tax plugin and by the invoice formatter, so the country the VAT was
computed on and the country the invoice legend is written for are always the same one.

## Presentation

| Property | Default |
|---|---|
| `taxItemDescription` | `VAT {rate}` |
| `legend.reverseCharge` | see example properties |
| `legend.outsideScope` | see example properties |

`{rate}` and `{country}` are substituted. The description becomes the TAX item's description,
which is what the invoice shows, and what the formatter groups the VAT summary by.

The two `legend.*` properties are carried on the resolved `VatTreatment` and returned by
`GET /simulate`, so a custom formatter or a downstream system can print them. The bundled
`config/invoice-template.mustache` does **not** read them: Mustache cannot select between wordings,
so the template holds the UK wording inline and branches on
`invoice.reverseCharge` / `outsideScope` / `zeroRated` instead. If you change `legend.*`, change
the template to match, or the invoice and the API will disagree.

## Invoice formatter properties

These are Kill Bill **system** properties (`killbill.properties` or `-D`), not tenant plugin
configuration, because Kill Bill 0.24.x builds a formatter with no tenant context.

| Property | Default | Meaning |
|---|---|---|
| `org.killbill.billing.plugin.vat.formatter.tenantId` | none | Legacy. Only affects invoices raised before the plugin began recording the treatment on the tax item, and pins the formatter to one tenant. Leave unset on a multi-tenant server. |
| `org.killbill.billing.plugin.vat.formatter.supplierCountry` | `GB` | Which country counts as domestic when choosing a legend. |

Without a valid `tenantId` the formatter cannot read the account at all, so it knows neither the
customer's country nor their VAT number. It does not guess: `invoice.vatTreatmentUnknown` becomes
true, the specific legends are all suppressed, and the template prints a bare "no VAT charged"
note. The alternative, guessing, prints a domestic zero-rating notice on what is really a
reverse-charge invoice.

`supplierCountry` should match the tax plugin's own `supplierCountry`. It only decides "is this
customer overseas"; the notice wording in the bundled template is written for a UK supplier and
needs rewording if yours is established elsewhere.

### Template values the formatter adds

| Key | Meaning |
|---|---|
| `invoice.formattedNetTotal` | Charges and charge adjustments, excluding VAT and credits. |
| `invoice.formattedVatTotal` | Every TAX item. |
| `invoice.formattedGrandTotal` | Net + VAT. **Use this as the invoice total**, not `formattedChargedAmount`, which answers a different question and will not add up on an invoice carrying a credit. |
| `invoice.anyCredit`, `invoice.formattedCreditTotal` | `CBA_ADJ` and `CREDIT_ADJ`, shown on their own row. |
| `invoice.vatBreakdown` | One row per tax description: `description`, `formattedRate`, `formattedNetAmount`, `formattedVatAmount`. |
| `invoice.standardVat` / `reverseCharge` / `outsideScope` / `zeroRated` / `vatTreatmentUnknown` | At most one is true. |
| `invoice.formattedTaxPointDate` | Earliest service period start, falling back to the invoice date. |
| `invoice.customerVatNumber`, `invoice.customerCountry`, `invoice.supplierCountry` | |
| per line: `lineDescription`, `formattedQuantity`, `formattedUnitPrice`, `vatRateLabel`, `formattedNetAmount`, `formattedVatAmount` | |

This is one concrete advantage over AvaTax, which discards the rate percentage entirely: it
writes only the tax amount and the tax name, and the `rate` field on the TAX item is the taxable
item's unit price. A UK VAT invoice must show the rate per line, so with AvaTax it has to be
reconstructed by dividing. Here it is simply written.

## Per-call properties

| Plugin property | Effect |
|---|---|
| `KILLBILL_VAT_SKIP` | Any non-null value bypasses the plugin for that call. |

## Known limitations

1. **Adjustment and credit refunds are skipped.** Refunding VAT on an adjustment requires knowing
   which adjustments were already accounted for, which needs persisted plugin state. Until that
   exists the plugin never double-taxes and never auto-refunds. This is deliberate: the failure
   mode is a missing refund you can spot, not a repeated one you cannot.
2. **Rates live in config, not a database.** Fine while rates are maintained by engineers; the
   `VatRateSource` interface is the seam for moving them.
3. **No VIES client yet.** `EXTERNAL` mode reads a verdict someone else wrote.
4. **No location evidence reconciliation.** EU B2C formally requires two non-contradictory pieces
   of evidence. The plugin currently trusts the account country.
5. **Kill Bill 0.24.x only.** The 0.25 line changes `InvoicePluginApi` and moves to Jakarta.
6. **The invoice formatter needs a tenant id property** on 0.24.x, because that version's
   `InvoiceFormatterFactory` signature carries no `TenantContext`. 0.25 adds an overload that
   supplies one. Without it the formatter suppresses every treatment legend rather than guessing.
7. **Only `standard` rates are selected automatically.** Reduced and zero-rated supplies need a
   custom `VatTreatmentResolver`, because Kill Bill's catalogue has no tax-category field for one
   to read.

## Endpoints

All read-only, mounted under `/plugins/killbill-vat/`, authenticated the same way as any Kill Bill
request (the tenant is resolved from the API key and secret).

| Endpoint | Purpose |
|---|---|
| `GET /config` | The effective parsed configuration for the tenant, plus a `problems` array. |
| `GET /simulate` | The treatment, rate, reason and net/VAT/gross split for a hypothetical supply. |
| `GET /healthcheck` | 200 when the configuration is sound, 503 when `problems` is non-empty. |

### `GET /simulate` parameters

All optional, so it is usable by hand from a browser.

| Parameter | Default | Meaning |
|---|---|---|
| `country` | none | Customer country, ISO 3166-1 alpha-2. Omitting it exercises `unknownCountryTreatment`. |
| `vatNumber` | none | Customer VAT number, any format. |
| `validated` | `false` | Whether to treat that number as externally verified. |
| `amount` | `100.00` | Catalogue amount, read according to the resolved price mode. |
| `date` | today | Tax point, so historical rates can be checked. |
| `plan`, `product` | none | Used to resolve per-plan and per-product price mode overrides. |

The response echoes the resolved `priceMode` and whether the charge line would be rewritten, which
is the quickest way to confirm an inclusive-pricing override is actually taking effect.

### What `problems` reports

- Unrecognised property keys, which are otherwise silently ignored.
- Keys under `rates.` that match neither accepted shape, so the parser drops them.
- Rates that could not be parsed: the ambiguous bare number, a negative, `100%` or more.
- Two rate entries for the same country and kind whose validity windows overlap, so which one
  applies depends on configuration nobody meant to be significant.
- No rates at all, or no **standard** rate in force today for the supplier country. That second
  check is deliberately about the standard rate rather than the jurisdiction: a misspelt rate kind
  (`rates.GB.standrd`) registers the jurisdiction while leaving domestic supplies untaxed.
- A `priceMode.plans.*` or `priceMode.products.*` value that is neither `INCLUSIVE` nor
  `EXCLUSIVE`. `INCLUSIV` used to revert a plan to `EXCLUSIVE` in silence, which charges 20% on
  top of a price that already contains it.
- EU rates configured with `ossRegistered` false, so they are inert.
- `vatNumberValidation` set to `NONE` or `CHECKSUM`, neither of which proves a registration exists.
- A `rounding.mode` that is not symmetric about zero, or is not a rounding mode at all.
- An implausible `rounding.scale`.



## Tenants that do not charge VAT

The plugin is installed once for the whole server, and Kill Bill is multi tenanted. A tenant that
does not charge VAT configures nothing and is left out of `org.killbill.invoice.plugin`; it never
sees a tax item and is reported healthy, with `mode` `NOT_CONFIGURED`. Nothing on this page applies
to it.

`enabled=false` is for the other case: a tenant that has configuration uploaded and wants VAT
switched off without deleting it. That reports `mode` `DISABLED`, also healthy.
