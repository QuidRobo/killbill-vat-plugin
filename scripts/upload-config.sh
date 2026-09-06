#!/usr/bin/env bash
#
# Uploads a killbill-vat configuration to a tenant and reads it straight back, so a typo is
# visible immediately rather than at quarter end.
#
#   ./scripts/upload-config.sh config/vat-uk-only.properties
#
# Environment:
#   KB_URL         default http://127.0.0.1:8080
#   KB_USER        default admin
#   KB_PASSWORD    required
#   KB_API_KEY     required
#   KB_API_SECRET  required
#
set -euo pipefail

KB_URL="${KB_URL:-http://127.0.0.1:8080}"
KB_USER="${KB_USER:-admin}"
CONFIG="${1:-}"

if [[ -z "$CONFIG" ]]; then
  echo "usage: $0 <config.properties>" >&2
  exit 1
fi
if [[ ! -f "$CONFIG" ]]; then
  echo "error: no such file: $CONFIG" >&2
  exit 1
fi
for required in KB_PASSWORD KB_API_KEY KB_API_SECRET; do
  if [[ -z "${!required:-}" ]]; then
    echo "error: $required is not set" >&2
    exit 1
  fi
done

auth=(-u "${KB_USER}:${KB_PASSWORD}"
      -H "X-Killbill-ApiKey: ${KB_API_KEY}"
      -H "X-Killbill-ApiSecret: ${KB_API_SECRET}")

echo "Uploading ${CONFIG} to ${KB_URL}"
curl --fail --silent --show-error -X POST \
  "${auth[@]}" \
  -H "X-Killbill-CreatedBy: $(whoami)" \
  -H "Content-Type: text/plain" \
  --data-binary "@${CONFIG}" \
  "${KB_URL}/1.0/kb/tenants/uploadPluginConfig/killbill-vat"

echo
echo "Reading it back:"
curl --fail --silent --show-error "${auth[@]}" \
  "${KB_URL}/plugins/killbill-vat/config" | (command -v jq >/dev/null && jq . || cat)

echo
echo "Anything listed under \"problems\" above needs fixing before you bill anyone."
echo
echo "Try a decision:"
echo "  curl -u '${KB_USER}:*****' -H 'X-Killbill-ApiKey: ${KB_API_KEY}' -H 'X-Killbill-ApiSecret: *****' \\"
echo "    '${KB_URL}/plugins/killbill-vat/simulate?country=DE&vatNumber=DE811234567&validated=true&amount=120.00'"
