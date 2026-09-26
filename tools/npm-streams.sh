#!/usr/bin/env bash
# Creates the Nginx Proxy Manager (NPM) streams needed to run KDEC Bridge in
# direct mode through a relay.
#
#   control: EXT_CONTROL                             -> COMPUTER:1716
#   payload: 1739+OFFSET .. PAYLOAD_LAST+OFFSET      -> COMPUTER:1739 .. PAYLOAD_LAST
#
# All streams are TCP. Identity injection happens on the phone, so the relay
# only carries the control channel and file transfer connections.
#
# Usage:
#   NPM_USER=admin@example.com NPM_PASS=... COMPUTER=192.168.1.20 tools/npm-streams.sh
set -euo pipefail

NPM_URL="${NPM_URL:-http://127.0.0.1:81}"   # NPM admin API base URL
NPM_USER="${NPM_USER:?set NPM_USER to the NPM admin email}"
NPM_PASS="${NPM_PASS:?set NPM_PASS to the NPM admin password}"
COMPUTER="${COMPUTER:?set COMPUTER to the computer address NPM can reach, e.g. 192.168.1.20}"
EXT_CONTROL="${EXT_CONTROL:-51716}"
OFFSET="${OFFSET:-50000}"
# kdeconnectd uses the first free port in 1739-1764 and releases it when the
# transfer ends, so transfers almost always use 1739. Five ports cover
# concurrent transfers; set PAYLOAD_LAST=1764 to expose the full range.
PAYLOAD_LAST="${PAYLOAD_LAST:-1743}"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

resp="$(mktemp)"
trap 'rm -f "$resp"' EXIT

echo "==> authenticating to $NPM_URL as $NPM_USER"
TOKEN=$(curl -sS -X POST "$NPM_URL/api/tokens" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg i "$NPM_USER" --arg s "$NPM_PASS" '{identity:$i,secret:$s}')" \
  | jq -r '.token // empty')
[ -n "$TOKEN" ] || { echo "authentication failed: check NPM_URL and credentials" >&2; exit 1; }
echo "    ok"

mkstream() {  # $1 = incoming (public) port, $2 = destination port on the computer
  local body code
  body=$(jq -nc --argjson in "$1" --arg host "$COMPUTER" --argjson fwd "$2" \
    '{incoming_port:$in, forwarding_host:$host, forwarding_port:$fwd,
      tcp_forwarding:true, udp_forwarding:false, certificate_id:0, meta:{}}')
  code=$(curl -sS -o "$resp" -w '%{http_code}' \
    -X POST "$NPM_URL/api/nginx/streams" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "$body")
  case "$code" in
    2*) echo "    + $1 -> $COMPUTER:$2" ;;
    *)  echo "    ! $1 -> $COMPUTER:$2  (HTTP $code) $(jq -rc '.error.message // .' "$resp" 2>/dev/null)" ;;
  esac
}

echo "==> control stream"
mkstream "$EXT_CONTROL" 1716

echo "==> payload streams 1739-$PAYLOAD_LAST (offset $OFFSET)"
for p in $(seq 1739 "$PAYLOAD_LAST"); do
  mkstream $(( p + OFFSET )) "$p"
done

echo
echo "Done. In the app, clear 'Use Tailscale (tsnet)' and set:"
echo "  Computer address        : <relay host>"
echo "  kdeconnectd port        : $EXT_CONTROL"
echo "  Payload port offset     : $OFFSET"
