#!/usr/bin/env bash
# M8 hand trial: puts a few persistent messages into the 3.13 dev broker's queues through ITS management API (developer
# tooling only; the app never writes to a 3.x broker). A queue that already holds messages is left alone, so running
# this twice does not double the counts. Throwaway password, same as the rest of dev/. Needs curl.
set -euo pipefail
BASE="${BROKER_3X_URL:-http://localhost:15673}"
AUTH="legacy-admin:dev-password"

# The container is "healthy" before the boot-time definitions import has finished, so wait for the first queue.
for _ in $(seq 1 30); do
  curl -fsS -u "$AUTH" "$BASE/api/queues/%2F/orders" >/dev/null 2>&1 && break
  sleep 2
done

publish() { # queue count [priority-levels]
  local queue="$1" count="$2" levels="${3:-}" i props held
  held=$(curl -fsS -u "$AUTH" "$BASE/api/queues/%2F/$queue" | sed -n 's/.*"messages":\([0-9]*\).*/\1/p' | head -n1)
  if [ "${held:-0}" -gt 0 ]; then echo "$queue: already holds $held messages, left alone"; return; fi
  for i in $(seq 1 "$count"); do
    props='"delivery_mode":2'
    if [ -n "$levels" ]; then props="$props,\"priority\":$(( (i % levels) + 1 ))"; fi
    curl -fsS -u "$AUTH" -H 'content-type: application/json' -X POST "$BASE/api/exchanges/%2F/amq.default/publish" \
      -d "{\"properties\":{$props},\"routing_key\":\"$queue\",\"payload\":\"$queue message $i\",\"payload_encoding\":\"string\"}" >/dev/null
  done
  echo "$queue: published $count messages"
}

publish orders 5
publish invoices 3
publish priority-jobs 4 5
publish ha.reports 2
publish quorum-jobs 3
