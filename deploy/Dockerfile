# syntax=docker/dockerfile:1
#
# Kill Bill with the QuidRobo VAT plugin and invoice formatter baked in.
#
# Railway gives each deploy a fresh, empty filesystem, so anything copied into a running
# container is gone at the next deploy. Installing plugins through Kaui or KPM at runtime would
# survive exactly until the next push. The bundles therefore belong in the image.
#
# Point a Railway service at this repository and set the Dockerfile path to deploy/Dockerfile
# (Settings -> Build -> Dockerfile Path, or RAILWAY_DOCKERFILE_PATH=deploy/Dockerfile).

# ---------------------------------------------------------------- build the bundles
# JDK 11 because killbill-oss-parent 0.146.63 targets 11, and the bundle has to run on the
# server's JVM. Tests run here, so a broken build fails the deploy rather than shipping.
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /src
COPY . .
RUN mvn -B -ntp clean package

# ---------------------------------------------------------------- Kill Bill image
# Pin the SAME tag your Railway service runs today. Do not use :latest: the plugin is compiled
# against the 0.24.x API and the 0.25 line changes InvoicePluginApi and moves to Jakarta.
FROM killbill/killbill:0.24.15

ARG PLUGIN_VERSION=1.0.0-SNAPSHOT

# Set explicitly so the image's own default does not matter. The platform default is
# /var/tmp/bundles, which on some images is a temp path that does not survive.
ENV KB_org_killbill_osgi_bundle_install_dir=/var/lib/killbill/bundles

# Kill Bill scans <install.dir>/plugins/java/<pluginName>/<version>/ at startup. The plugin name
# has to match OSGIPluginProperties.PLUGIN_NAME_PROP in each activator: killbill-vat and
# killbill-vat-formatter.
#
# Each jar is named in full rather than globbed. The build also produces -sources, -tests and
# -test-sources jars in the same target directory, and a glob would drop all four into the
# bundles directory for Kill Bill to try to start.
COPY --from=build /src/vat-plugin/target/killbill-vat-plugin-${PLUGIN_VERSION}.jar \
     /var/lib/killbill/bundles/plugins/java/killbill-vat/${PLUGIN_VERSION}/
COPY --from=build /src/vat-invoice-formatter/target/killbill-vat-invoice-formatter-${PLUGIN_VERSION}.jar \
     /var/lib/killbill/bundles/plugins/java/killbill-vat-formatter/${PLUGIN_VERSION}/

# Lets Kaui's Plugin Manager list and start/stop these by key. The plugins load from the
# directory structure above with or without this file; without it they simply do not appear in
# the plugin management UI.
COPY deploy/plugin_identifiers.json /var/lib/killbill/bundles/plugins/plugin_identifiers.json
