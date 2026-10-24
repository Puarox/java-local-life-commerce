"""Inject a missing-order payment event and verify finite retries plus dead-letter routing."""

import json
import os
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

import requests


MGMT = os.getenv("RABBITMQ_MANAGEMENT_URL", "http://127.0.0.1:15672").rstrip("/")
if MGMT not in ("http://127.0.0.1:15672", "http://localhost:15672"):
    raise SystemExit("DLQ test only permits loopback RabbitMQ Management")
USER = os.getenv("RABBITMQ_USER", "guest")
PASSWORD = os.getenv("RABBITMQ_PASSWORD", "guest")
VHOST = quote(os.getenv("RABBITMQ_VHOST", "/"), safe="")
QUEUE = "trade.pay.success.dlq"
MESSAGE_ID = "dlq-test-" + uuid.uuid4().hex

management = requests.Session()
management.auth = (USER, PASSWORD)
management.headers["content-type"] = "application/json"


def queue_state():
    response = management.get(f"{MGMT}/api/queues/{VHOST}/{QUEUE}", timeout=10)
    response.raise_for_status()
    return response.json()


before = queue_state()
before_ready = before["messages_ready"]
publish = management.post(f"{MGMT}/api/exchanges/{VHOST}/pay.direct/publish", json={
    "properties": {
        "delivery_mode": 2, "content_type": "application/json", "message_id": MESSAGE_ID,
        "headers": {"outboxEventId": MESSAGE_ID},
    },
    "routing_key": "pay.success",
    "payload": str(2**63 - 1),
    "payload_encoding": "string",
}, timeout=10)
publish.raise_for_status()
if not publish.json().get("routed"):
    raise AssertionError("RabbitMQ did not route the injected event to a payment consumer queue")

deadline = time.monotonic() + 30
message = None
while time.monotonic() < deadline:
    state = queue_state()
    if state["messages_ready"] > 0:
        response = management.post(f"{MGMT}/api/queues/{VHOST}/{QUEUE}/get", json={
            "count": state["messages_ready"], "ackmode": "ack_requeue_true",
            "encoding": "auto", "truncate": 50000,
        }, timeout=10)
        response.raise_for_status()
        messages = response.json()
        message = next((candidate for candidate in messages
                        if (candidate.get("properties") or {}).get("message_id") == MESSAGE_ID), None)
        if message is not None:
            break
    time.sleep(0.5)
else:
    raise AssertionError("Message did not reach the DLQ after the configured retry attempts")

properties = message.get("properties") or {}
headers = properties.get("headers") or {}
x_death = headers.get("x-death") or []
if not x_death or x_death[0].get("queue") != "trade.pay.success.queue.v2":
    raise AssertionError(f"Missing dead-letter provenance: {x_death}")

summary = {
    "date": datetime.now(timezone.utc).date().isoformat(),
    "scenario": "payment event for a nonexistent order exhausts listener retries and routes to DLQ",
    "sourceQueue": "trade.pay.success.queue.v2",
    "deadLetterQueue": QUEUE,
    "messageId": MESSAGE_ID,
    "payload": message.get("payload"),
    "retryPolicy": {"maxAttempts": 3, "initialInterval": "1s", "multiplier": 2.0,
                    "maxInterval": "5s", "requeueRejected": False},
    "deadLetterProvenance": x_death[0],
    "messagesReadyBefore": before_ready,
    "messagesReadyAfter": state["messages_ready"],
    "messageLeftInDlqForManualInspection": True,
}
output = Path("docs/verification/results")
output.mkdir(parents=True, exist_ok=True)
path = output / f"rabbitmq-dlq-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')}.json"
path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({key: value for key, value in summary.items() if key != "deadLetterProvenance"},
                 ensure_ascii=False))
print(f"raw: {path}")
