# Deploying to Railway

Railway hands every deploy a fresh filesystem. Anything you copy into a running container, or
install through Kaui's Plugin Manager, disappears at the next push. So the bundles go into the
image, and everything else is either an environment variable or per-tenant data in the database.

## 1. Build the image

Point a Railway service at this repository and set the Dockerfile path to `deploy/Dockerfile`
(Settings, Build, Dockerfile Path, or `RAILWAY_DOCKERFILE_PATH=deploy/Dockerfile`).

That service becomes your Kill Bill service. If you would rather keep the plugin repository and
the deployment separate, copy `deploy/` into a small `killbill-deploy` repository and have its
build stage pull the plugin from a GitHub release instead of building it in place.

Before the first build, open `deploy/Dockerfile` and pin `FROM killbill/killbill:` to the tag
your service runs today. The plugin is compiled against the 0.24.x API; the 0.25 line changes
`InvoicePluginApi` and moves to Jakarta, so `:latest` will eventually break it.

## 2. Environment variables

Two mechanisms work on this image. `KB_org_killbill_dao_user` maps to `org.killbill.dao.user`,
which is fine for all-lowercase property names. For the camelCase ones below, use `CATALINA_OPTS`
instead: the documented `KB_` rule does not say whether case survives the conversion, and a
property whose name has been lowercased is a property nobody reads.

```
CATALINA_OPTS=-Dorg.killbill.template.invoiceFormatterFactoryPluginName=killbill-vat-formatter -Dorg.killbill.template.invoice.defaultLocale=en_GB -Dorg.killbill.billing.plugin.vat.formatter.supplierCountry=GB
KB_org_killbill_osgi_system_bundle_export_packages_extra=org.killbill.billing.invoice.template.formatters
```

`export_packages_extra` is all lowercase, so `KB_` is safe for it, and it is the one you cannot
omit. `DefaultInvoiceFormatter` lives in a core package that is not exported to OSGi bundles by
default: without this the formatter bundle starts cleanly and then throws
`NoClassDefFoundError` on the first invoice render.

`formatter.tenantId` is **optional and only affects historical invoices.** Set it to nothing on a
multi-tenant server.

The background: killbill-plugin-api 0.27.3 declares
`InvoiceFormatterFactory.createInvoiceFormatter` with seven parameters and no `TenantContext`
(verified against the 0.27.3 tag; the eight-parameter overload arrives in 0.28.x, which is Kill
Bill 0.25+). A formatter therefore cannot name a tenant, so it cannot read the account, so it used
to need a tenant id configured here. That pinned the formatter to one tenant, which is wrong on the
exact kind of deployment Kill Bill is built for.

That is no longer how it works. The tax plugin runs with a real tenant context and already knows
the treatment, the customer's country and their VAT number at the moment it taxes the invoice, so
it now records them on the tax item's `itemDetails`. Kill Bill persists that on
`invoice_items.item_details` and returns it with the invoice, and the invoice is the one thing the
formatter is always handed. No account read, no tenant id, no configuration, and correct for every
tenant on the server.

A tax item is now emitted for every taxable charge, including reverse-charge, outside-scope and
zero-rated supplies which previously produced no item at all and therefore left no record of why
the VAT was zero. The zero-amount item is invisible on the invoice: `getLineItems()` iterates
charges only and the VAT summary table is gated on a non-zero VAT total.

`formatter.tenantId` now changes exactly one thing: whether an invoice raised **before** this
shipped can still render its legend, for the single tenant named. Current invoices ignore it.

## 3. Per-tenant data

These live in the database, not in the image, so they survive deploys and are set once:

These are per tenant on purpose: VAT is optional, and a tenant that does not charge it skips this
whole section. Skipping it is a supported state, not a half-finished deploy, and the plugin reports
such a tenant as healthy with `mode` `NOT_CONFIGURED`.

- Invoice plugin registration: Kaui, Tenant, Settings, or
  `POST /1.0/kb/tenants/uploadPerTenantConfig` with body
  `{"org.killbill.invoice.plugin":"killbill-vat"}`. Kill Bill calls no invoice plugin that is not
  in this list, so without it every invoice is 0% VAT while the plugin reports itself healthy.
  Each call replaces the whole per-tenant config, so include anything else already set there.
- Plugin configuration: Kaui, Tenant Configuration, Plugin Config, upload
  `config/vat-uk-only.properties` under the plugin name `killbill-vat`.
- Invoice template: Kaui, Tenant Configuration, Invoice Template, upload
  your branded copy of `config/invoice-template.mustache`.

## 4. Verify

```
GET  https://<your-service>/plugins/killbill-vat/healthcheck
GET  https://<your-service>/plugins/killbill-vat/config
GET  https://<your-service>/plugins/killbill-vat/simulate?country=DE&vatNumber=DE136695976&validated=true&amount=120.00
```

`/healthcheck` returns 200 with an empty `problems` array when the configuration is sound and 503
when it is not, so it works as a Railway healthcheck path.

Two things make that safe to point a platform healthcheck at. A request with no Kill Bill API
credentials carries no tenant, and Railway sends none, so the container check reports only that the
plugin is running and can never be reddened by one tenant's configuration. And a tenant that does
not charge VAT is reported healthy rather than unconfigured, so adding a tenant that does not use
the plugin does not take the service down.

In the deploy logs, look for the two bundles starting and for
`VAT plugin added N item(s) to invoice ...` on the first invoice run. If you set a tenant id
that does not match the account's tenant, you will instead see
`... cannot be rendered with a VAT treatment legend`.
