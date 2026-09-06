# killbill-vat-plugin

VAT for SaaS on [Kill Bill](https://killbill.io): UK, EU and rest of world, with automatic
place-of-supply determination for electronically supplied services, VAT-inclusive **or**
VAT-exclusive catalogue pricing, and invoices that are actually valid VAT invoices.

Apache 2.0. Built for Kill Bill **0.24.x**.

---

## Why this exists

Two Kill Bill tax plugins already exist and both stop short of the same thing.

| | [simple-tax](https://github.com/bgandon/killbill-simple-tax-plugin) | [easytax](https://github.com/SolarNetwork/killbill-easytax-plugin) | this |
|---|---|---|---|
| Last commit | Jun 2017 | Mar 2021 (archived) | live |
| Kill Bill era | 0.16 | 0.22 | 0.24 |
| Uses `PluginTaxCalculator` | no, hand-rolled | yes | yes |
| Pluggable customer-side resolution | no, hardcoded | zone string only | full treatment |
| VAT number read during computation | **no** | no | yes |
| Reverse charge | no | no | yes |
| VAT-inclusive pricing | no | no | yes |

Multiplying an amount by a rate is trivial. Deciding **which** rate applies to **this** customer
is the work, and for digital services that means answering four questions in order:

1. Is the customer VAT registered, and has that registration actually been checked?
2. B2B or B2C?
3. Where is the place of supply?
4. Therefore: domestic VAT, reverse charge at 0%, the customer's own country rate under OSS, or
   outside scope?

That decision is the product. It lives behind one interface, [`VatTreatmentResolver`](vat-plugin/src/main/java/com/quidrobo/killbill/vat/resolve/VatTreatmentResolver.java),
and swapping it is one config property.

## VAT-inclusive pricing

Kill Bill has no notion of tax-inclusive prices. Core, the plugin API and `PluginTaxCalculator`
all treat catalogue amounts as net and append tax. The AvaTax plugin's own DTO carries a
`taxIncluded` flag that is never assigned.

This plugin supports both, per tenant, per product, or per plan:

```properties
org.killbill.billing.plugin.vat.priceMode = EXCLUSIVE
org.killbill.billing.plugin.vat.priceMode.plans.consumer-monthly = INCLUSIVE
```

| Catalogue price | Mode | Charge line | TAX item | Invoice total |
|---|---|---|---|---|
| 120.00 | `EXCLUSIVE` | 120.00 | 24.00 | **144.00** |
| 120.00 | `INCLUSIVE` | 100.00 | 20.00 | **120.00** |

Inclusive mode works by returning the charge item with the **same id** and a rewritten amount.
Kill Bill's `InvoicePluginDispatcher` treats a returned item whose id already exists as an
update, and `amount` is one of the fields a plugin may change. The gross charge becomes a net
charge and the TAX item makes up the difference, so the customer pays exactly what they were
quoted and the invoice still carries a real TAX item for reporting.

VAT is computed as `gross - net`, never as `gross * rate / (1 + rate)`. Rounding the two halves
independently lets them disagree by a penny: on 9.99 at 20% that would produce 8.33 + 1.67 =
10.00, a penny more than the advertised price. The tests pin this.

> **Status:** the in-place amount rewrite is a novel mechanism. It follows directly from Kill
> Bill's own field-mutability rules and the `DefaultInvoiceDao` comment that anticipates plugins
> changing amounts, but no core test exercises it. Verify against your own Kill Bill before
> trusting it in production, and see [docs/CONFIGURATION.md](docs/CONFIGURATION.md#vat-inclusive-pricing).

## What it does

- **UK domestic VAT** at the standard rate, effective-dated.
- **B2B reverse charge** for EU business customers with a validated VAT number, with the legally
  required wording and their VAT number on the invoice.
- **Outside scope** for business customers beyond the reverse charge area. Both are 0%, but they
  are not the same thing and the invoice says which.
- **EU B2C** at the customer's own country rate, ready for OSS.
- **Rest of world** driven by data: until a registration exists for a country, supplies there are
  outside scope. Adding Norway is a config line, not a release.
- **Offline VAT number validation**: structure for every EU country plus GB, with check digits
  implemented for GB, DE, IT, NL and FR.
- **VAT-compliant invoices** via a companion `InvoiceFormatterFactory` bundle that supplies the
  net/VAT/gross totals and per-line VAT rate that a logic-less Mustache template cannot compute.

## Design decisions worth knowing

**An unvalidated VAT number is not a reverse charge.** It falls through to the B2C branch and VAT
is charged. Treating an unverified number as proof of business status is how suppliers end up
owing VAT they never collected.

**An unknown customer country charges domestic VAT** by default. Under-collecting is the
expensive direction of this error: the supplier carries the liability, while over-collecting is a
refund. Configurable via `unknownCountryTreatment` if you prefer the other risk.

**A missing rate is not 0%.** A configuration gap resolves to `OUTSIDE_SCOPE` with a reason
naming the gap, so it shows up in the logs and on the invoice rather than quietly costing money
until someone notices.

**Every treatment carries a reason.** When a tax authority asks in three years why an invoice
carried no VAT, "outside scope, customer country US, no registration for US on that date" is an
answer. A bare 0% is not.

## Configuring it, and knowing that you did

Configuration is a `.properties` payload uploaded per tenant, the standard Kill Bill mechanism,
hot-reloaded on `TENANT_CONFIG_CHANGE`. The problem with that mechanism is that it is write-only:
a typo in a property key is not an error, it is a key nobody reads, and every supply quietly
becomes outside scope. For a tax plugin that failure shows up as money at quarter end rather than
as an error at deploy time.

So the plugin serves three read-only endpoints under `/plugins/killbill-vat/`.

**`GET /config`** returns the effective, parsed configuration **and everything wrong with it**:

```json
{
  "supplierCountry": "GB",
  "defaultPriceMode": "EXCLUSIVE",
  "ossRegistered": false,
  "rates": [{ "jurisdiction": "GB", "kind": "standard", "percent": "20%", "validFrom": "2011-01-04" }],
  "problems": [
    "Unrecognised property 'org.killbill.billing.plugin.vat.roundng.scale'. It is being ignored; check the spelling against docs/CONFIGURATION.md.",
    "No standard rate is in force today for the supplier country GB, so domestic supplies will resolve to OUTSIDE_SCOPE and no VAT will be charged on them."
  ]
}
```

**`GET /simulate`** answers "what would you charge this customer, and why", without touching an
invoice:

```
/plugins/killbill-vat/simulate?country=DE&vatNumber=DE811234567&validated=true&amount=120.00
```

```json
{
  "decision": {
    "treatment": "REVERSE_CHARGE",
    "rate": 0,
    "invoiceLegend": "Reverse charge: VAT to be accounted for by the recipient.",
    "reason": "validated VAT number DE811234567 in DE"
  },
  "amounts": { "net": 120.00, "vat": 0.00, "gross": 120.00 }
}
```

That `reason` is the same string the calculator records when it builds the real tax item, so what
you see here is what the invoice run would have decided. Test a tax position before you bill
anyone with it.

**`GET /healthcheck`** returns 503 when the configuration has problems, so a bad upload reaches
monitoring rather than an accountant. A `Healthcheck` service is registered too, for other plugins.

### Starter configurations

- **`config/vat-uk-only.properties`** is complete and ready to upload as it stands. UK domestic
  VAT, EU B2B reverse charge, outside scope for everyone else. That is the whole correct position
  for a UK supplier with no OSS registration.
- **`config/vat-uk-eu-oss.properties`** is a **checklist**, not a configuration. Every EU rate is
  set to `TODO`, which the plugin cannot parse, so `GET /config` lists exactly which countries you
  still have to fill in. Rates are not shipped pre-filled on purpose: they move on legislative
  timelines, and a plausible-but-stale rate is worse than a missing one, because a missing one is
  reported and a wrong one silently under-collects.

```bash
./scripts/upload-config.sh config/vat-uk-only.properties
```

That script uploads and then immediately reads the configuration back, so a typo is visible in the
same command.

## Install

```bash
mvn clean install

kpm install_java_plugin killbill-vat \
  --from-source-file=vat-plugin/target/killbill-vat-plugin-1.0.0-SNAPSHOT.jar \
  --destination=/var/lib/killbill/bundles

kpm install_java_plugin killbill-vat-formatter \
  --from-source-file=vat-invoice-formatter/target/killbill-vat-invoice-formatter-1.0.0-SNAPSHOT.jar \
  --destination=/var/lib/killbill/bundles
```

Kill Bill configuration:

```properties
org.killbill.template.invoiceFormatterFactoryPluginName=killbill-vat-formatter
org.killbill.osgi.system.bundle.export.packages.extra=org.killbill.billing.invoice.template.formatters
org.killbill.template.invoice.defaultLocale=en_GB
```

The `export.packages.extra` line is **mandatory and easy to miss**. `DefaultInvoiceFormatter`
lives in a core package that is not exported to OSGi bundles by default, so the import falls
under the parent pom's trailing `*;resolution:=optional`. Without it the bundle installs cleanly
and then throws `NoClassDefFoundError` on the first invoice render.

Then upload the tenant configuration:

```bash
curl -X POST \
  -u admin:password \
  -H 'X-Killbill-ApiKey: <key>' -H 'X-Killbill-ApiSecret: <secret>' \
  -H 'X-Killbill-CreatedBy: admin' \
  -H 'Content-Type: text/plain' \
  --data-binary @vat-plugin/src/main/resources/vat.example.properties \
  http://127.0.0.1:8080/1.0/kb/tenants/uploadPluginConfig/killbill-vat
```

Full reference: [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## Customer data

Kill Bill's `Account` has no VAT number field, so the B2B facts live in account custom fields:

| Field | Purpose |
|---|---|
| `customerVatNumber` | The registration number, any format |
| `customerVatNumberValidatedAt` | ISO date of the last successful external check |
| `customerIsBusiness` | Explicit B2B flag for businesses that are not VAT registered |
| `customerTaxCountry` | Overrides the billing country for place of supply |

## Tests

The pure logic (price modes, rate resolution, treatment decisions, VAT number checks) has no Kill
Bill dependencies and is covered by 76 assertions. Because the build needs Maven Central and some
environments cannot reach it, there is also an offline check that compiles the whole plugin
against hand-written API stubs and runs the assertions with nothing but a JDK:

```bash
dev/offline-check/run.sh
```

That is a compile and logic check, not a substitute for building against the real jars.

## Roadmap

1. ✅ Rates, price modes, treatment resolution, invoice formatter
2. VIES client with caching, stored consultation numbers and a review queue for the down case
3. Database-backed rates and registrations, replacing config
4. Persisted tax state, so adjustments and credits refund VAT correctly
5. REST endpoints for rates, registrations and location evidence
6. EU B2C location evidence reconciliation (two non-contradictory pieces)

Known limitations are listed in [docs/CONFIGURATION.md](docs/CONFIGURATION.md#known-limitations).

## Credits

Design and prior art, both Apache 2.0:

- [bgandon/killbill-simple-tax-plugin](https://github.com/bgandon/killbill-simple-tax-plugin),
  Copyright 2015-2017 Benjamin Gandon. Effective-dated rate model, and VAT number checksum rules.
- [SolarNetwork/killbill-easytax-plugin](https://github.com/SolarNetwork/killbill-easytax-plugin),
  Copyright 2017 SolarNetwork Foundation. The pluggable resolver pattern.

## Licence

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
