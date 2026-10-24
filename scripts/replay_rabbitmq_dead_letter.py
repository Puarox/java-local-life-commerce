"""Replay exactly one isolated DLQ event while preserving its idempotency key."""

import argparse
import os
from urllib.parse import quote

import requests


MGMT = os.getenv("RABBITMQ_MANAGEMENT_URL", "http://127.0.0.1:15672").rstrip("/")
if MGMT not in ("http://127.0.0.1:15672", "http://localhost:15672"):
    raise SystemExit("DLQ replay only permits loopback RabbitMQ Management")
VHOST = os.getenv("RABBITMQ_VHOST", "/")
VHOST_PATH = quote(VHOST, safe="")
DESTINATIONS = {
    "trade.pay.success.dlq": ("pay.success", "trade.pay.success.queue.v2"),
    "voucher.pay.success.dlq": ("voucher.pay.success", "voucher.pay.success.queue.v2"),
}

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("queue", choices=DESTINATIONS, help="DLQ containing the event")
parser.add_argument("message_id", help="Expected AMQP message_id / outbox event ID")
args = parser.parse_args()

session = requests.Session()
session.auth = (os.getenv("RABBITMQ_USER", "guest"), os.getenv("RABBITMQ_PASSWORD", "guest"))
session.headers["content-type"] = "application/json"
queue_url = f"{MGMT}/api/queues/{VHOST_PATH}/{args.queue}"
state_response = session.get(queue_url, timeout=10)
state_response.raise_for_status()
state = state_response.json()
if state["messages_ready"] != 1 or state["messages_unacknowledged"] != 0:
    raise SystemExit("Replay is limited to a queue with exactly one ready message and no in-flight messages")

peek_response = session.post(queue_url + "/get", json={
    "count": 1, "ackmode": "ack_requeue_true", "encoding": "auto", "truncate": 50000,
}, timeout=10)
peek_response.raise_for_status()
peek = peek_response.json()
if len(peek) != 1:
    raise SystemExit("Could not read exactly one DLQ event")
event = peek[0]
properties = event.get("properties") or {}
if properties.get("message_id") != args.message_id:
    raise SystemExit(f"Queue head is {properties.get('message_id')!r}, not {args.message_id!r}")

headers = properties.get("headers") or {}
x_death = headers.get("x-death") or []
expected_routing_key, expected_source_queue = DESTINATIONS[args.queue]
if not x_death or x_death[0].get("queue") != expected_source_queue:
    raise SystemExit("DLQ provenance does not match the selected consumer queue")
if x_death[0].get("routing-keys", [None])[0] != expected_routing_key:
    raise SystemExit("DLQ provenance does not match the expected source routing key")

replay_headers = {key: value for key, value in headers.items()
                  if key not in ("x-death", "x-first-death-exchange", "x-first-death-queue",
                                 "x-first-death-reason", "x-last-death-exchange",
                                 "x-last-death-queue", "x-last-death-reason")}
publish_response = session.post(
    f"{MGMT}/api/exchanges/{VHOST_PATH}/{quote(x_death[0]['exchange'], safe='')}/publish",
    json={
        "properties": {
            "delivery_mode": properties.get("delivery_mode", 2),
            "content_type": properties.get("content_type", "application/json"),
            "message_id": properties["message_id"],
            "headers": replay_headers,
        },
        "routing_key": expected_routing_key,
        "payload": event["payload"],
        "payload_encoding": event.get("payload_encoding", "string"),
    }, timeout=10,
)
publish_response.raise_for_status()
if not publish_response.json().get("routed"):
    raise SystemExit("Replay was not routed; original DLQ event remains available")

# Publish first, then ack the original: a crash can cause a duplicate, so the
# consumer's idempotency check and the preserved message_id remain essential.
ack_response = session.post(queue_url + "/get", json={
    "count": 1, "ackmode": "ack_requeue_false", "encoding": "auto", "truncate": 50000,
}, timeout=10)
ack_response.raise_for_status()
acked = ack_response.json()
if len(acked) != 1 or (acked[0].get("properties") or {}).get("message_id") != args.message_id:
    raise SystemExit("Replay published, but the expected DLQ copy could not be acknowledged")
print(f"Replayed {args.message_id} to pay.direct/{expected_routing_key}; original DLQ copy acknowledged")
