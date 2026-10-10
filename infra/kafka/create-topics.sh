#!/bin/bash
# Idempotently creates every Rydex topic. Run by the one-shot kafka-init container.
# DLTs get the same partition count as their source topic because Spring's
# DeadLetterPublishingRecoverer publishes to the same partition number by default.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:29092}"
KT=/opt/kafka/bin/kafka-topics.sh
DAY_MS=86400000

create() { # name partitions retention_ms
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1 \
    --config retention.ms="$3"
}

create ride.events          6 $((7 * DAY_MS))
create ride.events.DLT      6 $((7 * DAY_MS))
create driver.location      6 3600000
create driver.location.DLT  6 $((7 * DAY_MS))
create payment.events       3 $((7 * DAY_MS))
create payment.events.DLT   3 $((7 * DAY_MS))

"$KT" --bootstrap-server "$BOOTSTRAP" --list
