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
CATALINA_OPTS=-Dorg.killbill.template.invoiceFormatterFactoryPluginName=killbill-vat-formatter -Dorg.killbill.template.invoice.defaultLocale=en_GB -Dorg.killbill.billing.plugin.vat.formatter.tenantId=<your tenant id> -Dorg.killbill.billing.plugin.vat.formatter.supplierCountry=GB
KB_org_killbill_osgi_system_bundle_export_packages_extra=org.killbill.billing.invoice.template.formatters
```

`export_packages_extra` is all lowercase, so `KB_` is safe for it, and it is the one you cannot
omit. `DefaultInvoiceFormatter` lives in a core package that is not exported to OSGi bundles by
default: without this the formatter bundle starts cleanly and then throws
`NoClassDefFoundError` on the first invoice render.

`formatter.tenantId` exists because Kill Bill 0.24.x hands an `InvoiceFormatterFactory` no
`TenantContext`, so the formatter cannot read account custom fields without being told which
tenant to use. Leaving it unset is safe: the invoice still renders, but the customer VAT number
and the VAT treatment legend are suppressed rather than guessed at. It also makes the formatter
single-tenant, so revisit this if you ever run more than one tenant.

## 3. Per-tenant data

These live in the database, not in the image, so they survive deploys and are set once:

- Plugin configuration: Kaui, Tenant Configuration, Plugin Config, upload
  `config/vat-uk-only.properties` under the plugin name `killbill-vat`.
- Invoice template: Kaui, Tenant Configuration, Invoice Template, upload
  your branded copy of `config/invoice-template.html`.

## 4. Verify

```
GET  https://<your-service>/plugins/killbill-vat/healthcheck
GET  https://<your-service>/plugins/killbill-vat/config
GET  https://<your-service>/plugins/killbill-vat/simulate?country=DE&vatNumber=DE136695976&validated=true&amount=120.00
```

`/healthcheck` returns 200 with an empty `problems` array when the configuration is sound and
503 when it is not, so it works as a Railway healthcheck path once a tenant is configured.

In the deploy logs, look for the two bundles starting and for
`VAT plugin added N item(s) to invoice ...` on the first invoice run. If you set a tenant id
that does not match the account's tenant, you will instead see
`... cannot be rendered with a VAT treatment legend`.
