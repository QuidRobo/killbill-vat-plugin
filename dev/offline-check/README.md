# Offline check

`./run.sh` compiles every plugin source file against hand-written Kill Bill API stubs and runs
the logic assertions, with no Maven and no network.

**These stubs are not the real API.** They exist so the plugin can be type-checked and its
arithmetic exercised in environments that cannot reach Maven Central. Signatures mirror
killbill-api 0.54.0, killbill-plugin-api 0.27.3, killbill-base-plugin 5.1.9 and
killbill-platform 0.41.18, but method bodies return null and nothing is behaviourally faithful.

Always run `mvn clean install` before releasing.

If a stub disagrees with the real API, the real API is right: fix the stub.
