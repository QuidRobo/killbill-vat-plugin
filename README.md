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
- **VAT number handling** that defers to whoever owns the customer record. See
  [Where validation belongs](#where-validation-belongs) below, because this is the part people get
  wrong.
- **VAT-compliant invoices** via a companion `InvoiceFormatterFactory` bundle that supplies the
  net/VAT/gross totals and per-line VAT rate that a logic-less Mustache template cannot compute.
- **Diagnostics that answer "what would you charge, and why"** before you bill anyone: a config
  read-back that lists everything wrong with your configuration, and a simulate endpoint that
  returns the treatment, the rate and the reasoning for a hypothetical customer.

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

**A configured rate is not authority to charge it.** `ossRegistered` defaults to false, and until
it is deliberately turned on an EU consumer will not be charged their own country rate even if you
have configured one. Collecting German VAT with no One Stop Shop registration means collecting tax
you have no mechanism to remit, which is worse than not charging it.

## Where validation belongs

**This plugin does not call VIES, and in its intended production configuration it does no VAT
number checking at all.** That is deliberate, and it is the single most important thing to
understand before wiring it up.

Validation belongs in whatever system owns the customer record, for three reasons:

1. It is the only layer that cannot be bypassed. A browser check is a convenience.
2. It can call VIES **asynchronously**. An invoice run that waits on the Commission's SOAP service
   fails whenever they have a bad afternoon, and they have them.
3. It is where the audit trail lives: which number, checked when, and with what answer.

So the plugin reads two account custom fields and trusts them:

| Custom field | Meaning |
|---|---|
| `customerVatNumber` | The registration number |
| `customerVatNumberValidatedAt` | When it last passed an external check |

`vatNumberValidation` controls how much proof is demanded:

- **`EXTERNAL`** (use this in production): the plugin checks nothing itself. A missing or stale
  `customerVatNumberValidatedAt` means no reverse charge, and VAT is charged. Over-collecting is a
  refund; under-collecting is a liability the supplier carries.
- **`CHECKSUM`**: for adopters with no such system in front of Kill Bill. The plugin then does its
  own structural and check-digit validation offline, covering every EU country plus GB and XI, with
  check digits for GB, DE, IT, NL and FR. Well-formed is not the same as registered, so this is a
  typo filter, not proof.
- **`NONE`**: development only.

### Two things that surprise people about VIES

**A UK supplier cannot obtain a consultation number.** VIES issues its `requestIdentifier`, the
thing that actually proves in an audit that you checked, only to requesters that are themselves EU
VAT registered. GB left that scheme with Brexit. You still get a valid/invalid answer, but your
audit trail is your own dated record of the request and the reply.

**GB numbers are not in VIES at all** and cannot be checked through it. HMRC runs a separate
service. For a UK supplier this costs nothing, since a GB customer is domestic and pays UK VAT
regardless of registration.

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
/plugins/killbill-vat/simulate?country=DE&vatNumber=DE136695976&validated=true&amount=120.00
```

```json
{
  "decision": {
    "treatment": "REVERSE_CHARGE",
    "rate": 0,
    "invoiceLegend": "Reverse charge: VAT to be accounted for by the recipient.",
    "reason": "validated VAT number DE136695976 in DE"
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

Two bundles: the tax plugin, and the invoice formatter. Both are optional to each other. The tax
plugin computes VAT with no formatter installed; the formatter only changes how invoices render.

**From a shell, if you have filesystem access to the server:**

```bash
mvn clean install

kpm install_java_plugin killbill-vat \
  --from-source-file=vat-plugin/target/killbill-vat-plugin-1.0.0-SNAPSHOT.jar \
  --destination=/var/lib/killbill/bundles

kpm install_java_plugin killbill-vat-formatter \
  --from-source-file=vat-invoice-formatter/target/killbill-vat-invoice-formatter-1.0.0-SNAPSHOT.jar \
  --destination=/var/lib/killbill/bundles
```

**From Kaui, if you do not** (hosted Kill Bill, containers, anything where you cannot drop a file
on disk). Kaui's *Upload plugin* form takes an HTTPS URI, so publish a GitHub release and give it
the asset URL. `.github/workflows/release.yml` does that on a version tag and prints the exact
values to paste.

| Field | Tax plugin | Formatter |
|---|---|---|
| Plugin key | `killbill-vat` | `killbill-vat-formatter` |
| Version | e.g. `1.0.0` | e.g. `1.0.0` |
| URI | release asset URL | release asset URL |
| Type | Java | Java |

> **Use those plugin keys, not the artifact names.** Per-tenant configuration is stored under
> `PLUGIN_CONFIG_<pluginName>`, where `pluginName` is the name the activator registers:
> **`killbill-vat`**. Install it under `killbill-vat-plugin` (the jar's name) and Kaui will show
> you that, Kill Bill's own docs will tell you the upload path segment is "the name on the
> filesystem", and your configuration will land in a key nothing reads. The plugin then runs on
> defaults, which means no rates, which means every supply resolves to `OUTSIDE_SCOPE` at 0%.
> `GET /config` reports the key it actually read, so check there rather than guessing.

On a platform with an ephemeral filesystem, such as Railway or any plain container deploy, a UI
install disappears at the next deploy. See [deploy/README.md](deploy/README.md) for baking the
bundles into an image, and for the environment-variable form of the properties below.

Kill Bill configuration:

```properties
org.killbill.template.invoiceFormatterFactoryPluginName=killbill-vat-formatter
org.killbill.osgi.system.bundle.export.packages.extra=org.killbill.billing.invoice.template.formatters
org.killbill.template.invoice.defaultLocale=en_GB

# The formatter reads account custom fields, and Kill Bill 0.24.x hands it no tenant context.
org.killbill.billing.plugin.vat.formatter.tenantId=<your tenant id>
org.killbill.billing.plugin.vat.formatter.supplierCountry=GB
```

Without `formatter.tenantId` the formatter cannot read the account, so it suppresses the VAT
treatment legend and the customer VAT number rather than guessing at them. It will not print a
domestic zero-rating notice on what is really a reverse-charge invoice.

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
  --data-binary @config/vat-uk-only.properties \
  http://127.0.0.1:8080/1.0/kb/tenants/uploadPluginConfig/killbill-vat
```

Full reference: [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## The invoice template

`config/invoice-template.html` is a Mustache template covering five cases from one file, driven by
flags the formatter exposes:

| Flag | Case |
|---|---|
| `invoice.domesticVat` | VAT at the supplier's own rate. An ordinary home sale. |
| `invoice.destinationVat` | VAT at the **customer's** country rate: EU B2C under the One Stop Shop, or a country with a local registration. The money is that country's VAT, and the invoice says so. |
| `invoice.reverseCharge` | 0%, business customer accounts for the VAT, their number printed. |
| `invoice.outsideScope` | 0%, place of supply outside the supplier's country. |
| `invoice.zeroRated` | 0% to a domestic customer, e.g. a fully credited invoice. |
| `invoice.vatTreatmentUnknown` | The formatter could not read the account, so no legal statement is made. Fix `formatter.tenantId`. |

Edit the supplier block, the footer, the logo and the notice wording (search for `TODO`), then
upload it. Kaui: Tenant Configuration, Invoice Template. Or `POST /1.0/kb/invoices/template` with
`Content-Type: text/html`. The template is not stored per locale; translations are separate.

Set `taxItemDescription = {country} VAT {rate}` so the VAT summary names the taxing country rather
than just the rate, which is what makes an EU invoice readable.

**Upload it last.** Mustache silently skips sections whose key does not resolve, so a template
uploaded before the formatter is installed renders with no totals block, no VAT summary and no
notices. It looks broken rather than erroring.

## Customer data

Kill Bill's `Account` has no VAT number field, so the B2B facts live in account custom fields:

| Field | Purpose |
|---|---|
| `customerVatNumber` | The registration number, any format |
| `customerVatNumberValidatedAt` | ISO date of the last successful external check |
| `customerTaxCountry` | Overrides the billing country for place of supply |

There is deliberately no "is a business" flag. What makes a supply B2B for VAT is a valid
registration number, not a self-declared status, and a checkbox anyone can tick is not evidence
any tax authority accepts.

## Tests

The pure logic (price modes, rate resolution, treatment decisions, configuration diagnostics, VAT
number checks) has no Kill Bill dependencies, so it is tested directly:

```bash
mvn test
```

57 tests in the tax plugin, 3 in the formatter. They run as part of `mvn clean install`, so a
contributor cannot get a green build having tested nothing.

Two things the tests deliberately do not cover, because both need a running Kill Bill rather than
a mock that would only assert what this plugin already believes:

- **The in-place amount rewrite** that makes VAT-inclusive pricing work. It follows from Kill
  Bill's field classification, but no core test exercises it. Verify it on your own server before
  you rely on it, and watch for the `Rewriting VAT-inclusive item` log line.
- **Invoice rendering.** Install the formatter, render an invoice in each of the five treatments,
  and check net + VAT equals the total.

## Troubleshooting

Every one of these has been hit for real.

**The bundle installs, then does not start.** Check the Kill Bill log, not Kaui's, for the lines
after `received START command`. `BundleException: Activator start error` with a
`NoClassDefFoundError` underneath means a package the bundle needs is not available at runtime.
The parent pom's trailing `*;resolution:=optional` makes such imports optional, so the bundle
resolves happily and only fails when the activator touches the class. This is why the plugin
carries its own copy of `killbill-utils`: `PluginTaxCalculator` references `MultiValueMap`, and
Kill Bill exports none of `org.killbill.commons.*` to bundles.

**Rates are configured but every supply is `OUTSIDE_SCOPE`.** `GET /config` now tells you which of
the two causes it is: nothing stored under the key it reads (upload under the plugin name, see the
Install note above), or stored but cached from before the upload (restart the plugin). The
per-tenant config is read lazily on first access and then cached until a `TENANT_CONFIG_CHANGE`
event arrives.

**`/config` or `/simulate` shows settings you never set.** Those endpoints resolve the tenant from
the `X-Killbill-ApiKey` and `X-Killbill-ApiSecret` headers. Opening the URL in a browser sends
neither, so you get the *default* configuration rather than your tenant's. The response says so
explicitly, in a `WARNING` field.

**Invoices render without totals or notices.** The formatter is not installed, or
`org.killbill.template.invoiceFormatterFactoryPluginName` is not set, so Kill Bill is using its
stock formatter and none of the keys the template needs resolve.

**The invoice has a heading but no VAT summary after an upgrade.** The template is newer than the
formatter jar. `domesticVat` and `destinationVat` were added after the first release; a template
using them against an older formatter renders those sections as nothing.

## Roadmap

1. Rates, price modes, treatment resolution, invoice formatter
2. Config read-back, simulate and healthcheck endpoints
3. An **optional** in-plugin VIES client, for adopters with no system upstream of Kill Bill to do
   it. Everyone else should validate where the customer record lives, and set `EXTERNAL`. Whoever
   builds this: make the call asynchronous and cached, never inside `getAdditionalInvoiceItems`,
   and treat an unreachable service as "unknown" rather than "invalid" so a Commission outage does
   not strip the reverse charge from legitimate customers.
4. Database-backed rates and registrations, replacing config
5. Persisted tax state, so adjustments and credits refund VAT correctly
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
